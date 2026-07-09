package com.imawx.mcp.gateway.service.mcpproxy.provider;

import java.util.List;
import java.util.Map;

/**
 * Provider 工具调用结果。
 *
 * @param content MCP content blocks
 * @param isError 工具业务层是否失败
 * @param metadata provider 需要附带的诊断信息
 */
public record McpProviderCallResult(
        List<Map<String, Object>> content,
        boolean isError,
        Map<String, Object> metadata
) {
}
