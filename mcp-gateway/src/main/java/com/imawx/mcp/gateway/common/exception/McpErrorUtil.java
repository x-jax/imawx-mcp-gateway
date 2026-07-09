package com.imawx.mcp.gateway.common.exception;

/**
 * MCP 协议错误解析工具。
 *
 * <p>远端抛 {@code McpError}（含 JSON-RPC error code），用于日志记录 / 业务层区分超时。
 */
public final class McpErrorUtil {

    private static final java.util.regex.Pattern MCP_ERROR_CODE =
            java.util.regex.Pattern.compile("McpError\\[code=(-?\\d+)");

    private McpErrorUtil() {
    }

    /** 从异常链提取 MCP 协议错误码（数字字符串），提取不到返回 null。 */
    public static String extractMcpErrorCode(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m == null) {
                continue;
            }
            var matcher = MCP_ERROR_CODE.matcher(m);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    public static boolean isTimeout(Throwable t) {
        if (t == null) {
            return false;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getClass().getName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }
}
