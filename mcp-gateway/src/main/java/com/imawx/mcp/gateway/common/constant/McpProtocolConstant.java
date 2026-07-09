package com.imawx.mcp.gateway.common.constant;

/**
 * MCP 2.0 协议方法名常量。
 *
 * <p>这些是 MCP 协议层 JSON-RPC 方法名，本服务作为代理透传时使用。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
public final class McpProtocolConstant {

    /** 握手：客户端发起，协商协议版本与能力。 */
    public static final String METHOD_INITIALIZE = "initialize";

    /** 握手完成通知：客户端在 initialize 之后发送。 */
    public static final String METHOD_NOTIFICATIONS_INITIALIZED = "notifications/initialized";

    /** 列出远端所有工具。 */
    public static final String METHOD_TOOLS_LIST = "tools/list";

    /** 调用某个工具。 */
    public static final String METHOD_TOOLS_CALL = "tools/call";

    /** 工具列表变更通知（远端 → 客户端）。 */
    public static final String METHOD_NOTIFICATIONS_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

    /** ping 保活。 */
    public static final String METHOD_PING = "ping";

    /** 列出远端所有资源。 */
    public static final String METHOD_RESOURCES_LIST = "resources/list";

    /** 读取某个资源。 */
    public static final String METHOD_RESOURCES_READ = "resources/read";

    /** 列出提示模板。 */
    public static final String METHOD_PROMPTS_LIST = "prompts/list";

    /** 获取某个提示模板。 */
    public static final String METHOD_PROMPTS_GET = "prompts/get";

    /** 日志级别通知。 */
    public static final String METHOD_NOTIFICATIONS_MESSAGE = "notifications/message";

    /** 进度通知。 */
    public static final String METHOD_NOTIFICATIONS_PROGRESS = "notifications/progress";

    private McpProtocolConstant() {
    }
}
