package com.imawx.mcp.gateway.common.exception;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import lombok.Getter;

/**
 * 业务异常，承载 {@link BizErrorCode}。
 *
 * <p>由 {@link GlobalExceptionHandler} 映射成 HTTP 响应：{@code R.fail(code, message)}。
 */
@Getter
public class BizException extends RuntimeException {

    private final BizErrorCode errorCode;

    public BizException(BizErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(BizErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(BizErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}