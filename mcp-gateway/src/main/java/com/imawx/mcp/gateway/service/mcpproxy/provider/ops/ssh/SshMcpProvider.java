package com.imawx.mcp.gateway.service.mcpproxy.provider.ops.ssh;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.annotation.McpToolDefinition;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.support.BuiltinBackendProviderSupport;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class SshMcpProvider extends BuiltinBackendProviderSupport implements McpProvider {

    public static final String TRANSPORT_TYPE = "SSH";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    public SshMcpProvider(McpBackendService backendService,
                          McpBackendExtensionService extensionService,
                          BuiltinMcpDefinitionService definitionService) {
        super(backendService, extensionService, definitionService);
    }

    @Override
    public String serverType() {
        return TRANSPORT_TYPE;
    }

    @Override
    protected String transportType() {
        return TRANSPORT_TYPE;
    }

    @Override
    protected String providerLabel() {
        return "SSH";
    }

    @Override
    public McpProviderCallResult callTool(McpProviderCallRequest request) {
        McpBackendDO backend = backend(request.serverId());
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        Object data = switch (request.toolName()) {
            case "ssh_allowed_commands" -> Map.of("commands", allowedCommands(backend));
            case "ssh_probe" -> probe(backend);
            case "ssh_exec" -> exec(backend, required(args, "command"));
            default -> throw new BizException(BizErrorCode.NOT_FOUND, "SSH tool 不存在: " + request.toolName());
        };
        return textResult(data);
    }

    @McpToolDefinition(
            name = "ssh_probe",
            description = "测试 SSH 登录是否可用",
            inputSchema = """
                    {"type":"object","properties":{}}
                    """)
    private Object probe(McpBackendDO backend) {
        try (SSHClient ignored = openClient(backend, config(backend), secret(backend))) {
            return Map.of("ok", true);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "SSH 登录失败: " + e.getMessage());
        }
    }

    @McpToolDefinition(
            name = "ssh_exec",
            description = "执行白名单内的 SSH 命令",
            inputSchema = """
                    {"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}
                    """)
    private Object exec(McpBackendDO backend, String command) {
        List<String> allowed = allowedCommands(backend);
        String normalized = command.trim();
        if (!allowed.isEmpty() && !allowed.contains(normalized)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "SSH 命令不在白名单内: " + normalized);
        }
        Map<String, Object> cfg = config(backend);
        int timeoutSeconds = Math.max(1, intValue(cfg.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS));
        try (SSHClient ssh = openClient(backend, cfg, secret(backend));
             Session session = ssh.startSession()) {
            Session.Command cmd = session.exec(normalized);
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readLimited(cmd.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readLimited(cmd.getErrorStream()));
            cmd.join(timeoutSeconds, TimeUnit.SECONDS);
            boolean timedOut = cmd.getExitStatus() == null && cmd.getExitSignal() == null;
            if (timedOut) {
                try {
                    cmd.close();
                } catch (IOException ignored) {
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", normalized);
            out.put("exitStatus", cmd.getExitStatus());
            out.put("timedOut", timedOut);
            out.put("stdout", limitOutput(awaitOutput(stdout)));
            out.put("stderr", limitOutput(awaitOutput(stderr)));
            out.put("timeoutSeconds", timeoutSeconds);
            return out;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "SSH 调用失败: " + e.getMessage());
        }
    }

    private SSHClient openClient(McpBackendDO backend, Map<String, Object> cfg, Map<String, Object> sec) throws IOException {
        String host = firstNonBlank(str(cfg, "host"), parseEndpointHost(backend.getEndpoint()));
        int port = intValue(cfg.get("port"), parseEndpointPort(backend.getEndpoint(), 22));
        String username = firstNonBlank(str(cfg, "username"), "root");
        int timeoutMs = Math.max(1, intValue(cfg.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS)) * 1000;
        if (host == null || host.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "SSH Host 未配置");
        }
        SSHClient ssh = new SSHClient();
        String hostKeyFingerprint = str(cfg, "hostKeyFingerprint");
        ssh.addHostKeyVerifier(hostKeyFingerprint.isBlank()
                ? new PromiscuousVerifier()
                : FingerprintVerifier.getInstance(hostKeyFingerprint));
        ssh.setConnectTimeout(timeoutMs);
        ssh.setTimeout(timeoutMs);
        ssh.connect(host, port);
        String authType = firstNonBlank(str(cfg, "authType"), "PASSWORD").toUpperCase();
        String credential = firstNonBlank(str(sec, "authToken"), str(sec, "password"), str(sec, "privateKey"));
        if (credential == null || credential.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "SSH 凭据未配置");
        }
        if ("PRIVATE_KEY".equals(authType)) {
            OpenSSHKeyFile keyFile = new OpenSSHKeyFile();
            keyFile.init(new StringReader(credential), null, null);
            ssh.authPublickey(username, keyFile);
            return ssh;
        }
        ssh.authPassword(username, credential);
        return ssh;
    }

    @McpToolDefinition(
            name = "ssh_allowed_commands",
            description = "列出该 SSH MCP 允许执行的命令",
            inputSchema = """
                    {"type":"object","properties":{}}
                    """)
    private List<String> allowedCommands(McpBackendDO backend) {
        Object raw = config(backend).get("allowedCommands");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
        }
        if (raw instanceof String s) {
            return Arrays.stream(s.split("[\\r\\n,;]+"))
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private static String limitOutput(byte[] bytes) {
        int len = Math.min(bytes.length, MAX_OUTPUT_BYTES);
        String text = new String(bytes, 0, len, StandardCharsets.UTF_8);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            return text + "\n...[truncated]";
        }
        return text;
    }

    private static byte[] awaitOutput(CompletableFuture<byte[]> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return ("[output unavailable: " + e.getClass().getSimpleName() + "]").getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] readLimited(InputStream in) {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(MAX_OUTPUT_BYTES, 8192));
        int total = 0;
        try {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                int remaining = MAX_OUTPUT_BYTES - total;
                if (remaining > 0) {
                    int copy = Math.min(n, remaining);
                    out.write(buffer, 0, copy);
                    total += copy;
                }
            }
        } catch (IOException e) {
            if (out.size() == 0) {
                return ("[read failed: " + e.getMessage() + "]").getBytes(StandardCharsets.UTF_8);
            }
        }
        return out.toByteArray();
    }

    private static String parseEndpointHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return null;
        try {
            return java.net.URI.create(endpoint).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int parseEndpointPort(String endpoint, int fallback) {
        if (endpoint == null || endpoint.isBlank()) return fallback;
        try {
            int port = java.net.URI.create(endpoint).getPort();
            return port > 0 ? port : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
