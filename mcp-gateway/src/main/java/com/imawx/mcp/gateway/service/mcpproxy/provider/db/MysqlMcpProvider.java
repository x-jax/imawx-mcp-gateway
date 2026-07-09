package com.imawx.mcp.gateway.service.mcpproxy.provider.db;

import com.imawx.mcp.gateway.service.dbmcp.McpDbConnectionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import org.springframework.stereotype.Component;

@Component
public class MysqlMcpProvider extends RelationalDbMcpProviderSupport {
    public MysqlMcpProvider(McpBackendService backendService,
                            McpBackendExtensionService extensionService,
                            McpDbConnectionService dbConnectionService,
                            BuiltinMcpDefinitionService definitionService) {
        super("MYSQL", backendService, extensionService, dbConnectionService, definitionService);
    }
}
