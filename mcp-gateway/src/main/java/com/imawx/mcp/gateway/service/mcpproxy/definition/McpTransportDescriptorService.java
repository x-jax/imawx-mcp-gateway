package com.imawx.mcp.gateway.service.mcpproxy.definition;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService.BuiltinTransportDefinition;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService.ConditionalSecretRequired;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService.RequiredConfig;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService.ResourceField;
import com.imawx.mcp.gateway.service.system.McpSystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class McpTransportDescriptorService {
    private static final String CONFIG_STDIO_ALLOWED_COMMANDS = "mcp.security.stdio.allowed-commands";

    private final BuiltinMcpDefinitionService definitionService;
    private final McpGatewayProperties properties;
    private final McpSystemConfigService configService;

    public void validateTransport(String transportType) {
        if (transportType == null || transportType.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "transportType 不能为空");
        }
        if (!definitionService.supportsTransport(transportType)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "transportType 不合法: " + transportType);
        }
    }

    public boolean isExtensionTransport(String transportType) {
        BuiltinTransportDefinition definition = definitionService.transport(transportType);
        return definition != null && definition.isExtensionTransport();
    }

    public String secretKey(String transportType) {
        BuiltinTransportDefinition definition = requireTransport(transportType);
        return firstNonBlank(definition.secretKey(), "authToken");
    }

    public List<String> secretConfigKeys(String transportType) {
        return requireTransport(transportType).secretConfigKeys();
    }

    public void validateEndpoint(String transportType, String endpoint) {
        BuiltinTransportDefinition definition = requireTransport(transportType);
        if (endpoint == null || endpoint.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "endpoint 不能为空");
        }
        switch (definition.endpointPolicy()) {
            case "PASS" -> {
            }
            case "STDIO" -> validateStdioEndpoint(endpoint);
            case "URI" -> validateUriEndpoint(endpoint, definition);
            default -> throw new BizException(BizErrorCode.INTERNAL_ERROR,
                    "未知 MCP endpoint 校验策略: " + definition.endpointPolicy());
        }
    }

    public void validateExtensionConfig(String transportType, Map<String, Object> config, String authToken, boolean create) {
        BuiltinTransportDefinition definition = requireTransport(transportType);
        if (!definition.isExtensionTransport()) {
            return;
        }
        for (RequiredConfig item : definition.requiredConfig()) {
            validateRequiredConfig(config, item);
        }
        if (definition.secretRequiredMessage() != null) {
            requireSecret(authToken, create, definition.secretRequiredMessage());
        }
        ConditionalSecretRequired conditional = definition.secretRequiredWhenConfigNotEquals();
        if (conditional != null) {
            String actual = str(config.getOrDefault(conditional.key(), ""));
            if (!actual.equalsIgnoreCase(conditional.value())) {
                requireSecret(authToken, create, conditional.message());
            }
        }
    }

    public String resourceScope(McpBackendDO backend, Map<String, Object> config) {
        BuiltinTransportDefinition definition = definitionService.transport(backend.getTransportType());
        if (definition == null || definition.resourceFields().isEmpty()) {
            return scopeLine("endpoint", sanitizeEndpoint(backend.getEndpoint()));
        }
        List<String> scopes = new ArrayList<>();
        for (ResourceField field : definition.resourceFields()) {
            String value = resourceValue(field, backend, config);
            if (field.shouldSanitize()) {
                value = sanitizeEndpoint(value);
            }
            addScope(scopes, field.label(), value);
        }
        return scopes.isEmpty() ? null : String.join("; ", scopes);
    }

    private BuiltinTransportDefinition requireTransport(String transportType) {
        validateTransport(transportType);
        return definitionService.transport(transportType);
    }

    private void validateStdioEndpoint(String endpoint) {
        if (!properties.getSecurity().getBackendTarget().isAllowStdio()) {
            throw new BizException(BizErrorCode.FORBIDDEN,
                    "STDIO backend 被部署配置禁用，请先开启 mcp-gateway.security.backend-target.allow-stdio");
        }
        Set<String> allowedCommands = parseListConfig(configService.get(CONFIG_STDIO_ALLOWED_COMMANDS));
        if (allowedCommands.isEmpty()) {
            throw new BizException(BizErrorCode.FORBIDDEN,
                    "STDIO 命令白名单为空，请先在配置表设置 " + CONFIG_STDIO_ALLOWED_COMMANDS);
        }
        String normalized = endpoint.trim();
        if (!allowedCommands.contains(normalized)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "STDIO 命令不在白名单内: " + normalized);
        }
    }

    private void validateUriEndpoint(String endpoint, BuiltinTransportDefinition definition) {
        Set<String> schemes = definition.allowedSchemes().stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (schemes.isEmpty()) {
            throw new BizException(BizErrorCode.INTERNAL_ERROR,
                    "MCP URI endpoint 缺少 allowedSchemes: " + definition.transportType());
        }
        String label = firstNonBlank(definition.endpointLabel(), definition.transportType() + " endpoint");
        URI uri = parseEndpointUri(endpoint);
        String scheme = uri.getScheme();
        if (scheme == null || schemes.stream().noneMatch(s -> s.equalsIgnoreCase(scheme))) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                    label + " 只允许 " + schemes.stream().map(s -> s + "://").collect(Collectors.joining(" 或 ")));
        }
        if (definition.rejectUserInfo() && uri.getUserInfo() != null) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                    label + " 不允许包含 userInfo 凭据，请使用加密密钥字段");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, label + " host 不能为空");
        }
        validateResolvedAddresses(uri.getHost(), properties.getSecurity().getBackendTarget());
    }

    private void validateRequiredConfig(Map<String, Object> config, RequiredConfig item) {
        String type = item.type() == null ? "text" : item.type().trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "text" -> {
                if (str(config.get(item.key())).isBlank()) {
                    throw new BizException(BizErrorCode.INVALID_ARGUMENT, item.message());
                }
            }
            case "port" -> requirePort(config.get(item.key()), item.message());
            case "httpurl" -> requireHttpUrl(str(config.get(item.key())), item.message());
            case "listnonempty" -> {
                Object value = config.get(item.key());
                if (!(value instanceof List<?> list) || list.isEmpty()) {
                    throw new BizException(BizErrorCode.INVALID_ARGUMENT, item.message());
                }
            }
            default -> throw new BizException(BizErrorCode.INTERNAL_ERROR,
                    "未知 MCP 配置校验类型: " + item.type());
        }
    }

    private static void requireSecret(String authToken, boolean create, String message) {
        if (create && (authToken == null || authToken.isBlank())) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, message);
        }
    }

    private static void requirePort(Object value, String message) {
        int port;
        try {
            port = value instanceof Number n ? n.intValue() : (str(value).isBlank() ? -1 : Integer.parseInt(str(value)));
        } catch (NumberFormatException e) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, message);
        }
        if (port < 1 || port > 65535) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, message);
        }
    }

    private static void requireHttpUrl(String value, String message) {
        String v = value == null ? "" : value.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, message);
        }
    }

    private static Set<String> parseListConfig(String config) {
        if (config == null || config.isBlank()) {
            return Set.of();
        }
        return Pattern.compile("[,\\n\\r;]+")
                .splitAsStream(config)
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static URI parseEndpointUri(String endpoint) {
        try {
            return URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "endpoint URL 不合法: " + e.getMessage());
        }
    }

    private static void validateResolvedAddresses(
            String host,
            McpGatewayProperties.Security.BackendTarget policy) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (!policy.isAllowLoopback() && (address.isLoopbackAddress() || address.isAnyLocalAddress())) {
                    throw new BizException(BizErrorCode.FORBIDDEN, "endpoint 禁止指向本机/loopback 地址: " + host);
                }
                if (!policy.isAllowLinkLocal() && address.isLinkLocalAddress()) {
                    throw new BizException(BizErrorCode.FORBIDDEN, "endpoint 禁止指向 link-local 地址: " + host);
                }
                if (address.isMulticastAddress()) {
                    throw new BizException(BizErrorCode.FORBIDDEN, "endpoint 禁止指向 multicast 地址: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "endpoint host 无法解析: " + host);
        }
    }

    private static String resourceValue(ResourceField field, McpBackendDO backend, Map<String, Object> config) {
        String source = field.source() == null ? "config" : field.source();
        return switch (source) {
            case "hostPort" -> hostPort(config, backend.getEndpoint());
            case "configList" -> joinList(firstConfigured(config, field.keys()));
            case "first" -> firstNonBlank(field.keys().stream()
                    .map(key -> valueByPath(key, backend, config))
                    .toArray(String[]::new));
            case "config" -> firstNonBlank(field.keys().stream()
                    .map(key -> str(config.get(key)))
                    .toArray(String[]::new));
            default -> null;
        };
    }

    private static Object firstConfigured(Map<String, Object> config, List<String> keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String valueByPath(String key, McpBackendDO backend, Map<String, Object> config) {
        if ("endpoint".equals(key)) {
            return backend.getEndpoint();
        }
        if (key != null && key.startsWith("config.")) {
            return str(config.get(key.substring("config.".length())));
        }
        return str(config.get(key));
    }

    private static String hostPort(Map<String, Object> config, String endpoint) {
        String host = str(config.get("host"));
        String port = str(config.get("port"));
        if (!host.isBlank()) {
            return port.isBlank() ? host : host + ":" + port;
        }
        return sanitizeEndpoint(endpoint);
    }

    private static void addScope(List<String> scopes, String key, String value) {
        String line = scopeLine(key, value);
        if (line != null) {
            scopes.add(line);
        }
    }

    private static String scopeLine(String key, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return key + "=" + compact(value, 160);
    }

    private static String joinList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Objects::toString)
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .limit(20)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        if (value instanceof String s) {
            return s.trim();
        }
        return Objects.toString(value);
    }

    private static String sanitizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String trimmed = endpoint.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getUserInfo() == null) {
                return trimmed;
            }
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()).toString();
        } catch (Exception ignored) {
            return compact(trimmed.replaceAll("(?i)(password|pwd|token|secret)=([^;&\\s]+)", "$1=***"), 160);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? "" : Objects.toString(value).trim();
    }

    private static String compact(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, Math.max(0, max - 3)) + "...";
    }
}
