package com.imawx.mcp.gateway.service.mcpproxy.provider.support;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderServer;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class BuiltinBackendProviderSupport {

    protected final McpBackendService backendService;
    protected final McpBackendExtensionService extensionService;
    protected final BuiltinMcpDefinitionService definitionService;

    protected BuiltinBackendProviderSupport(McpBackendService backendService,
                                            McpBackendExtensionService extensionService,
                                            BuiltinMcpDefinitionService definitionService) {
        this.backendService = backendService;
        this.extensionService = extensionService;
        this.definitionService = definitionService;
    }

    protected abstract String transportType();

    protected abstract String providerLabel();


    public List<McpProviderServer> listEnabledServers() {
        return backendService.listEnabled().stream()
                .filter(b -> transportType().equalsIgnoreCase(b.getTransportType()))
                .map(b -> new McpProviderServer(transportType(), b.getBackendId(), b.getServerName(), true))
                .toList();
    }

    public List<McpProviderTool> listTools(String serverId) {
        McpBackendDO backend = backendService.selectByBackendId(serverId);
        if (backend == null || !transportType().equalsIgnoreCase(backend.getTransportType())) {
            return List.of();
        }
        return definitionService.requireTools(transportType());
    }

    protected McpBackendDO backend(String serverId) {
        McpBackendDO backend = backendService.selectByBackendId(serverId);
        if (backend == null || !transportType().equalsIgnoreCase(backend.getTransportType())
                || backend.getEnabled() == null || backend.getEnabled() != 1) {
            throw new BizException(BizErrorCode.NOT_FOUND, providerLabel() + " MCP 不存在或未启用: " + serverId);
        }
        return backend;
    }

    protected Map<String, Object> config(McpBackendDO backend) {
        return extensionService.config(backend.getId());
    }

    protected Map<String, Object> secret(McpBackendDO backend) {
        return extensionService.secret(backend.getId());
    }

    protected McpProviderCallResult textResult(Object data) {
        return new McpProviderCallResult(
                List.of(Map.of("type", "text", "text", JsonUtil.toJson(data))),
                false,
                Map.of("provider", transportType()));
    }

    protected static String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : Objects.toString(value).trim();
    }

    protected static String required(Map<String, Object> args, String key) {
        String value = str(args, key);
        if (value == null || value.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, key + " 不能为空");
        }
        return value;
    }

    protected static int intValue(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    protected static boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return fallback;
    }

    protected static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

}
