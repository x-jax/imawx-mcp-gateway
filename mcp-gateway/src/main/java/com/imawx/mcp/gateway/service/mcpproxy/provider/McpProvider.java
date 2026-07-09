package com.imawx.mcp.gateway.service.mcpproxy.provider;

import java.util.List;

/**
 * MCP 能力提供者扩展接口。
 *
 * <p>现有外部 MCP server、后续数据库管理模块、阿里云 MCP、腾讯云 MCP 等都应通过
 * provider 暴露 server/tool/call 能力，聚合层只依赖本接口。
 */
public interface McpProvider {

    /**
     * 服务类型编码，如 EXTERNAL、DB、ALIYUN_CLOUD、TENCENT_CLOUD。
     */
    String serverType();

    /**
     * 返回当前 provider 下启用的服务实例。
     */
    List<McpProviderServer> listEnabledServers();

    /**
     * 返回指定服务实例已暴露的工具。
     */
    List<McpProviderTool> listTools(String serverId);

    /**
     * 调用指定服务实例下的工具。
     */
    McpProviderCallResult callTool(McpProviderCallRequest request);
}
