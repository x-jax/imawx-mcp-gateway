package com.imawx.mcp.gateway.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录入参 DTO(2026-07-02 改)——
 *
 * <p>3 字段一次带过来,服务端按需校验 TOTP:
 * <ul>
 *   <li>{@code account} 必填 —— 邮箱(有 @ 走 email 唯一索引)/ username(无 @ 兼容老用户)</li>
 *   <li>{@code password} 必填 —— 明文密码,后端 BCrypt 比对</li>
 *   <li>{@code totpCode} 可选 —— 6 位 TOTP 验证码 或 8 个 backup code;
 *       用户启用了 2FA 时**必填**(后端返错提示);未启用时忽略</li>
 * </ul>
 *
 * <p>校验顺序(用户原话"先校验二次码再校验密码"):
 * <ol>
 *   <li>解析 account → 拿 user</li>
 *   <li>如果 totp_verified_at != null(已绑过 2FA) —— 校验 totpCode;错就直接 401,不再校验 password</li>
 *   <li>校验 password</li>
 *   <li>2026-07-04 加:全局 totp 开启 + 未绑过 → 抛 SetupRequiredException,前端跳 setup 页</li>
 *   <li>通过 → 写 session + 返 userInfo</li>
 * </ol>
 */
@Data
public class McpLoginDTO {

    @NotBlank(message = "邮箱不能为空")
    @Size(max = 128, message = "邮箱长度不能超过 128")
    private String account;

    @NotBlank(message = "密码不能为空")
    @Size(max = 128, message = "密码长度不能超过 128")
    private String password;

    /**
     * TOTP 验证码(可选)——
     * <ul>
     *   <li>用户未启用 2FA:留空,后端忽略</li>
     *   <li>用户启用了 2FA:必填 6 位 TOTP code(Google Authenticator)或 8 个 backup code(5+5 格式)</li>
     * </ul>
     */
    @Size(max = 11, message = "TOTP 6 位 或 备份码 11 字符(5+5 含 -)")
    private String totpCode;
}
