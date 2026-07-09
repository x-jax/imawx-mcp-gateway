package com.imawx.mcp.gateway.common.constant;

import com.imawx.mcp.gateway.common.enums.TransportType;

/**
 * 传输层相关常量。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
public final class TransportConstant {

    /** MCP Streamable HTTP 标准的消息端点。 */
    public static final String MCP_STREAMABLE_MESSAGE_ENDPOINT = "/mcp/message";

    /** MCP Streamable HTTP 标准的 SSE 端点。 */
    public static final String MCP_STREAMABLE_SSE_ENDPOINT = "/mcp/sse";

    /** 旧版 SSE 端点。 */
    public static final String MCP_SSE_ENDPOINT = "/sse";

    /** 后台管理 API 前缀（避开 MCP 协议端点）。 */
    public static final String ADMIN_API_PREFIX = "/api/admin";

    private TransportConstant() {
    }

    /**
     * 解析传输类型字符串为枚举。
     *
     * @param type 字符串
     * @return 枚举
     */
    public static TransportType parseType(String type) {
        return TransportType.fromCode(type);
    }
}
