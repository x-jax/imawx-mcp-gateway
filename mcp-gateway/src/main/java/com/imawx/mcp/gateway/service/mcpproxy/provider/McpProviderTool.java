package com.imawx.mcp.gateway.service.mcpproxy.provider;

/**
 * Provider 暴露给聚合层的工具摘要。
 *
 * @param name        provider 内原始 tool 名
 * @param description tool 描述
 * @param inputSchema MCP inputSchema JSON 字符串
 */
public record McpProviderTool(
        String name,
        String description,
        String inputSchema
) {
}
