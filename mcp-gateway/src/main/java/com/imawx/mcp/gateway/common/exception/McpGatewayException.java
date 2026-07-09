package com.imawx.mcp.gateway.common.exception;

import com.imawx.mcp.gateway.common.enums.McpErrorCode;
import lombok.Getter;

/**
 * 业务异常。所有可控异常抛本类，由 {@link GlobalExceptionHandler} 统一映射 HTTP 状态。
 */
@Getter
public class McpGatewayException extends RuntimeException {

    private final McpErrorCode errorCode;

    public McpGatewayException(McpErrorCode code, String message) {
        super(message);
        this.errorCode = code;
    }

    public McpGatewayException(McpErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = code;
    }
}
