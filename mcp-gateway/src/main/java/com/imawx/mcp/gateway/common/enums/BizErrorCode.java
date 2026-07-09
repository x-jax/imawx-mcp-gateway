package com.imawx.mcp.gateway.common.enums;

import lombok.Getter;

/**
 * 业务错误码。
 *
 * <p>格式：{@code NNNXX} —— 3 位 HTTP 状态 + 2 位场景子码。
 * <pre>
 * 40100 未登录
 * 40101 用户名密码错误
 * 40300 无权限
 * 40310 SQL 越权（账号隔离字段被绕过）
 * 40400 资源不存在
 * 40900 冲突（重复 / 引用未断）
 * 50000 服务异常（兜底）
 * 50300 下游熔断
 * </pre>
 *
 * <p>前端按子码特判场景，按 HTTP 段做通用处理（如 axios 拦截器看 401 → 跳登录页）。
 */
@Getter
public enum BizErrorCode {

    UNAUTHORIZED(40100, 401, "未登录"),
    BAD_CREDENTIALS(40101, 401, "用户名或密码错误"),
    ACCOUNT_DISABLED(40102, 401, "账号已停用"),
    /**
     * 2026-07-04 加:TOTP 总开关开启时,用户没初始化 2FA 拒绝登录(让用户找 admin 配)。
     */
    TOTP_NOT_INITIALIZED(40103, 401, "请联系管理员初始化两步验证"),

    FORBIDDEN(40300, 403, "无权限"),
    PASSWORD_CHANGE_REQUIRED(40301, 403, "请先修改初始密码"),
    SQL_FORBIDDEN(40310, 403, "越权访问"),

    NOT_FOUND(40400, 404, "资源不存在"),

    CONFLICT(40900, 409, "资源冲突"),

    INVALID_ARGUMENT(40000, 400, "参数非法"),

    BACKEND_DISABLED(40001, 400, "外部 MCP server 已停用"),

    BACKEND_BROKEN(50300, 503, "下游熔断"),
    MCP_INVOKE_TIMEOUT(50400, 504, "远端调用超时"),
    MCP_REMOTE_ERROR(50200, 502, "远端返回错误"),

    INTERNAL_ERROR(50000, 500, "服务异常");

    private final int code;
    private final int httpStatus;
    private final String message;

    BizErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
