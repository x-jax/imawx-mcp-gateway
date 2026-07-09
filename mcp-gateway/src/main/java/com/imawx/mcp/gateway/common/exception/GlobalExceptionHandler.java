package com.imawx.mcp.gateway.common.exception;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.response.R;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：业务异常按 {@link BizErrorCode} 映射 HTTP，校验失败 400，兜底 500。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> biz(BizException ex) {
        BizErrorCode c = ex.getErrorCode();
        log.warn("[biz] code={} msg={}", c.getCode(), ex.getMessage());
        return ResponseEntity.status(c.getHttpStatus()).body(R.fail(c.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[validation] {}", msg);
        return ResponseEntity.status(400).body(R.fail(BizErrorCode.INVALID_ARGUMENT.getCode(), msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> constraint(ConstraintViolationException ex) {
        return ResponseEntity.status(400).body(R.fail(BizErrorCode.INVALID_ARGUMENT.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> illegal(IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(R.fail(BizErrorCode.INVALID_ARGUMENT.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> methodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("[method-not-supported] method={} supported={}", ex.getMethod(), ex.getSupportedHttpMethods());
        return ResponseEntity.status(405).body(R.fail(BizErrorCode.INVALID_ARGUMENT.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> unknown(Exception ex) {
        log.error("[unhandled]", ex);
        return ResponseEntity.status(500).body(R.fail(BizErrorCode.INTERNAL_ERROR.getCode(), ex.getMessage()));
    }
}
