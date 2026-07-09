package com.imawx.mcp.gateway.service.mcpproxy.provider;

/**
 * Provider 暴露给聚合层的服务实例摘要。
 *
 * @param serverType 服务类型编码
 * @param serverId   provider 内稳定唯一的服务 id
 * @param name       展示名称
 * @param enabled    是否启用
 */
public record McpProviderServer(
        String serverType,
        String serverId,
        String name,
        boolean enabled
) {
}
