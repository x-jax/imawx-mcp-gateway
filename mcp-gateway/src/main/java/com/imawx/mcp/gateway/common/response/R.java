package com.imawx.mcp.gateway.common.response;

/**
 * 统一响应包装 {@code {code, message, data}}，与前端约定的形状一致。
 *
 * <p>设计：
 * <ul>
 *   <li>{@link #code} 镜像 HTTP 状态码（200 / 401 / 403 / 404 / 409 / 500）</li>
 *   <li>{@link #message} 业务消息（成功 / 失败的简要描述）</li>
 *   <li>{@link #data} 业务数据</li>
 *   <li>任何 Long 类型字段通过 {@code LongToStringSerializer} 输出为 string，避免 JS Number 精度丢失</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 */
public record R<T>(int code, String message, T data) {

    public static final int OK = 200;

    public static R<Void> ok() {
        return new R<>(OK, "OK", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(OK, "OK", data);
    }

    public static R<Void> ok(String message) {
        return new R<>(OK, message, null);
    }

    public static <T> R<T> ok(T data, String message) {
        return new R<>(OK, message, data);
    }

    public static R<Void> fail(int code, String message) {
        return new R<>(code, message, null);
    }
}