package com.imawx.mcp.gateway.service.mcpproxy.provider.db;

import com.imawx.mcp.gateway.service.dbmcp.McpDbConnectionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import org.springframework.stereotype.Component;

@Component
public class SqlServerMcpProvider extends RelationalDbMcpProviderSupport {
    public SqlServerMcpProvider(McpBackendService backendService,
                                McpBackendExtensionService extensionService,
                                McpDbConnectionService dbConnectionService,
                                BuiltinMcpDefinitionService definitionService) {
        super("SQLSERVER", backendService, extensionService, dbConnectionService, definitionService);
    }
}
