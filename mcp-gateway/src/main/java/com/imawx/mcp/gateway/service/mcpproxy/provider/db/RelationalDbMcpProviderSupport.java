package com.imawx.mcp.gateway.service.mcpproxy.provider.db;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.service.dbmcp.McpDbConnectionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderServer;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class RelationalDbMcpProviderSupport implements McpProvider {

    private final String transportType;
    private final McpBackendService backendService;
    private final McpBackendExtensionService extensionService;
    private final McpDbConnectionService dbConnectionService;
    private final BuiltinMcpDefinitionService definitionService;
    private static final Set<String> READ_TOOLS = Set.of("list_tables", "describe_table", "query_select");

    protected RelationalDbMcpProviderSupport(String transportType,
                                             McpBackendService backendService,
                                             McpBackendExtensionService extensionService,
                                             McpDbConnectionService dbConnectionService,
                                             BuiltinMcpDefinitionService definitionService) {
        this.transportType = transportType;
        this.backendService = backendService;
        this.extensionService = extensionService;
        this.dbConnectionService = dbConnectionService;
        this.definitionService = definitionService;
    }

    @Override
    public String serverType() {
        return transportType;
    }

    @Override
    public List<McpProviderServer> listEnabledServers() {
        return backendService.listEnabled().stream()
                .filter(b -> transportType.equalsIgnoreCase(b.getTransportType()))
                .map(b -> new McpProviderServer(transportType, b.getBackendId(), b.getServerName(), true))
                .toList();
    }

    @Override
    public List<McpProviderTool> listTools(String serverId) {
        McpBackendDO backend = backendService.selectByBackendId(serverId);
        if (backend == null || !transportType.equalsIgnoreCase(backend.getTransportType())) {
            return List.of();
        }
        return definitionService.requireTools(transportType).stream()
                .filter(t -> isToolAllowed(backend, t.name()))
                .toList();
    }

    @Override
    public McpProviderCallResult callTool(McpProviderCallRequest request) {
        McpBackendDO backend = backendService.selectByBackendId(request.serverId());
        if (backend == null || !transportType.equalsIgnoreCase(backend.getTransportType())
                || backend.getEnabled() == null || backend.getEnabled() != 1) {
            throw new BizException(BizErrorCode.NOT_FOUND, transportType + " 数据库 MCP 不存在或未启用: " + request.serverId());
        }
        boolean exists = definitionService.requireTools(transportType).stream()
                .anyMatch(t -> t.name().equals(request.toolName()));
        if (!exists) {
            throw new BizException(BizErrorCode.NOT_FOUND, transportType + " 数据库 MCP tool 不存在: " + request.toolName());
        }
        if (!isToolAllowed(backend, request.toolName())) {
            throw new BizException(BizErrorCode.FORBIDDEN,
                    backend.getServerName() + " 不允许执行数据库 tool: " + request.toolName());
        }
        Map<String, Object> result = dbConnectionService.callTool(
                backend,
                request.toolName(),
                request.arguments() == null ? Map.of() : request.arguments());
        return new McpProviderCallResult(
                List.of(Map.of("type", "text", "text", JsonUtil.toJson(result))),
                false,
                Map.of("provider", transportType));
    }

    private boolean isToolAllowed(McpBackendDO backend, String toolName) {
        Map<String, Object> config = extensionService.config(backend.getId());
        Set<String> allowed = allowedOperations(config);
        if (!allowed.isEmpty()) {
            return allowed.contains(toolName);
        }
        if (readOnly(config)) {
            return READ_TOOLS.contains(toolName);
        }
        return true;
    }

    private static boolean readOnly(Map<String, Object> config) {
        if (bool(config.get("readOnly"))) {
            return true;
        }
        String mode = str(config.get("mode"));
        if ("READ_ONLY".equalsIgnoreCase(mode) || "READONLY".equalsIgnoreCase(mode)) {
            return true;
        }
        Object permissions = config.get("permissions");
        if (permissions instanceof Map<?, ?> map) {
            return bool(map.get("readOnly")) || bool(map.get("readonly"));
        }
        return false;
    }

    private static Set<String> allowedOperations(Map<String, Object> config) {
        Object value = firstNonNull(config.get("allowedOperations"), config.get("allowedTools"));
        if (value instanceof Map<?, ?> map) {
            value = firstNonNull(map.get("allowedOperations"), map.get("allowedTools"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
        }
        String text = str(value);
        if (!text.isBlank()) {
            return java.util.regex.Pattern.compile("[,;\\n\\r]+")
                    .splitAsStream(text)
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
