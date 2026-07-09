package com.imawx.mcp.gateway.core;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.enums.TransportType;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MCP 客户端工厂。
 *
 * <p>不缓存连接池 — 每次 {@link #create} 返回新客户端，调用方负责关闭（见
 * {@link McpClientExecutor#withClient}）。
 *
 * <p>注意：MCP 2.0 弃用 {@code HttpClientSseClientTransport}，统一用
 * {@link HttpClientStreamableHttpTransport} 替代。协议层 SSE / Streamable HTTP 客户端实现合并。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpClientFactory {

    private static final McpSchema.Implementation CLIENT_INFO =
            McpSchema.Implementation.builder("imawx-mcp-gateway", "0.0.1")
                    .title("Imawx MCP Gateway")
                    .build();

    private final McpGatewayProperties properties;

    /**
     * 连接超时。只覆盖 TCP/TLS 握手阶段，避免远端不可达时被长请求超时拖住。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    public McpSyncClient create(McpBackendDO backend) {
        McpClientTransport transport = build(backend);
        Duration timeout = Duration.ofSeconds(properties.getMcp().getInvoke().getRequestTimeoutSeconds());
        Duration initTimeout = Duration.ofSeconds(properties.getMcp().getInvoke().getInitializeTimeoutSeconds());
        log.debug("[client-create] backendId={} transport={} timeoutSec={} initTimeoutSec={} connectTimeoutSec={}",
                backend.getBackendId(), backend.getTransportType(),
                timeout.toSeconds(), initTimeout.toSeconds(), CONNECT_TIMEOUT.toSeconds());
        return McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(initTimeout)
                .clientInfo(CLIENT_INFO)
                .build();
    }

    /**
     * 创建异步客户端，供流式调用订阅 logging/progress notification。
     */
    public McpAsyncClient createAsync(
            McpBackendDO backend,
            Consumer<McpSchema.LoggingMessageNotification> loggingHandler,
            Consumer<McpSchema.ProgressNotification> progressHandler
    ) {
        McpClientTransport transport = build(backend);
        Duration timeout = Duration.ofSeconds(properties.getMcp().getInvoke().getRequestTimeoutSeconds());
        Duration initTimeout = Duration.ofSeconds(properties.getMcp().getInvoke().getInitializeTimeoutSeconds());
        log.debug("[client-create-async] backendId={} transport={} timeoutSec={} initTimeoutSec={}",
                backend.getBackendId(), backend.getTransportType(), timeout.toSeconds(), initTimeout.toSeconds());
        McpClient.AsyncSpec spec = McpClient.async(transport)
                .requestTimeout(timeout)
                .initializationTimeout(initTimeout)
                .clientInfo(CLIENT_INFO);
        if (loggingHandler != null) {
            spec = spec.loggingConsumer(n -> {
                loggingHandler.accept(n);
                return reactor.core.publisher.Mono.fromRunnable(() -> {});
            });
        }
        if (progressHandler != null) {
            spec = spec.progressConsumer(n -> {
                progressHandler.accept(n);
                return reactor.core.publisher.Mono.fromRunnable(() -> {});
            });
        }
        return spec.build();
    }

    /**
     * 2026-07-06 重构:AUTO 探测逻辑删除。
     *
     * <p>前端表单需要 user 明确选 HTTP / SSE / STDIO transportType(不再有 AUTO 选项),
     * 这里就不再需要 {@code probe()} —— user 选啥直接 build 啥。
     */
    private McpClientTransport build(McpBackendDO b) {
        TransportType type = TransportType.fromCode(b.getTransportType());
        return switch (type) {
            case STDIO -> {
                String command = b.getEndpoint();
                require(command, "endpoint (command)");
                ServerParameters.Builder sb = ServerParameters.builder(command);
                ExtraConfig ec = parseExtraConfig(b.getExtraConfig());
                if (!ec.args().isEmpty()) {
                    sb.args(ec.args());
                }
                ec.env().forEach(sb::addEnvVar);
                yield new StdioClientTransport(sb.build(), McpJsonDefaults.getMapper());
            }
            // SSE 单独走老协议；HTTP / STREAMABLE_HTTP 走 MCP 2.0 Streamable HTTP。
            case SSE -> HttpClientSseClientTransport
                    .builder(require(b.getEndpoint(), "endpoint"))
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            case HTTP, STREAMABLE_HTTP ->
                    HttpClientStreamableHttpTransport.builder(require(b.getEndpoint(), "endpoint"))
                            .connectTimeout(CONNECT_TIMEOUT)
                            .build();
            default -> throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                    "内部 MCP transport 不应走外部 MCP client factory: " + type.getCode());
        };
    }

    static List<String> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JsonUtil.fromJson(json, new tools.jackson.core.type.TypeReference<List<String>>() {});
    }

    static Map<String, String> parseEnv(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return JsonUtil.fromJson(json, new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
    }

    /**
     * extra_config 解析：{"args":["..."], "env":{"K":"V"}}
     */
    static ExtraConfig parseExtraConfig(String json) {
        if (json == null || json.isBlank()) {
            return new ExtraConfig(List.of(), Map.of());
        }
        java.util.Map<String, Object> map = JsonUtil.fromJson(json,
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        if (map == null) {
            return new ExtraConfig(List.of(), Map.of());
        }
        List<String> args = parseArgsList(map.get("args"));
        Map<String, String> env = parseEnvMap(map.get("env"));
        return new ExtraConfig(args, env);
    }

    private static List<String> parseArgsList(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Map<String, String> parseEnvMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            java.util.Map<String, String> r = new java.util.HashMap<>();
            m.forEach((k, v) -> r.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            return r;
        }
        return Map.of();
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    public record ExtraConfig(List<String> args, Map<String, String> env) {
    }
}
