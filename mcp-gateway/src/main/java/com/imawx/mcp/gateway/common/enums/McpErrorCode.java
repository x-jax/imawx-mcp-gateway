package com.imawx.mcp.gateway.common.enums;

import lombok.Getter;

/**
 * 网关标准业务错误码。
 *
 * <p>每个错误码对应：
 * <ul>
 *   <li>稳定的字符串 code（落库 / 跨服务透传）</li>
 *   <li>默认 HTTP 状态（{@link #httpStatus}）</li>
 * </ul>
 *
 * <p>与 MCP 标准错误码的关系：MCP 远端返回的 {@code McpError} 统一映射到
 * {@link #MCP_REMOTE_ERROR}，原始 MCP error code 落到 {@code mcp_call_log.error_code} 字段。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Getter
public enum McpErrorCode {

    /** 下游不存在。 */
    BACKEND_NOT_FOUND("BACKEND_NOT_FOUND", 404),

    /** 下游已停用。 */
    BACKEND_DISABLED("BACKEND_DISABLED", 400),

    /** 下游处于熔断状态。 */
    BACKEND_BROKEN("BACKEND_BROKEN", 503),

    /** 远端工具不存在。 */
    TOOL_NOT_FOUND("TOOL_NOT_FOUND", 404),

    /** 调用超时。 */
    MCP_INVOKE_TIMEOUT("MCP_INVOKE_TIMEOUT", 504),

    /** 远端返回业务错误。 */
    MCP_REMOTE_ERROR("MCP_REMOTE_ERROR", 502),

    /** 入参非法（业务校验失败）。 */
    INVALID_ARGUMENT("INVALID_ARGUMENT", 400),

    /** 资源冲突（如 backendId 重复）。 */
    CONFLICT("CONFLICT", 409),

    /** 网关内部错误（兜底）。 */
    INTERNAL_ERROR("INTERNAL_ERROR", 500);

    private final String code;
    private final int httpStatus;

    McpErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }
}
