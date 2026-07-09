package com.imawx.mcp.gateway.service.mcpproxy.provider.db;

import com.imawx.mcp.gateway.service.dbmcp.McpDbConnectionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import org.springframework.stereotype.Component;

@Component
public class OracleMcpProvider extends RelationalDbMcpProviderSupport {
    public OracleMcpProvider(McpBackendService backendService,
                             McpBackendExtensionService extensionService,
                             McpDbConnectionService dbConnectionService,
                             BuiltinMcpDefinitionService definitionService) {
        super("ORACLE", backendService, extensionService, dbConnectionService, definitionService);
    }
}
