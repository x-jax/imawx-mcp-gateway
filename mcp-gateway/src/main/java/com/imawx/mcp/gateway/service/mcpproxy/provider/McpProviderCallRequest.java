package com.imawx.mcp.gateway.service.mcpproxy.provider;

import java.util.Map;

/**
 * Provider 工具调用上下文。
 */
public record McpProviderCallRequest(
        Long userId,
        Long tokenId,
        String serverType,
        String serverId,
        String toolName,
        Map<String, Object> arguments,
        String inboundSessionId,
        String clientIp,
        String userAgent
) {
}
