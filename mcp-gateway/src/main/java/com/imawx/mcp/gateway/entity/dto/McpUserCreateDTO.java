package com.imawx.mcp.gateway.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建用户 DTO —— admin 路径。
 *
 * <p>2026-07-03 改:username 由 caller 显式传入(原自动生成规则废弃)。
 * 原因:管理员建账号时,username 是用户在系统里的稳定身份标识(列表展示 / 老登录路径 /
 * 审计日志都会用),后端自动生成一串 `_xxxx` 既不可读也不可控,改由管理员手填更合理。
 *
 * <p>格式约束:3-32 位,字母开头,允许字母/数字/下划线 —— 跟 mcp_user.username UNIQUE 索引对齐,
 * 避免越界字符被 DB 截断或后续路径走老 SELECT 时匹配不上。
 */
@Data
public class McpUserCreateDTO {

    /**
     * 用户名(系统内唯一身份标识)—— 必填。
     * <p>3-32 位,字母开头,后续允许字母 / 数字 / 下划线。
     */
    @NotBlank(message = "username 不能为空")
    @Size(min = 3, max = 32, message = "username 长度 3-32 位")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
            message = "username 只能包含字母、数字、下划线,且必须以字母开头")
    private String username;

    @NotBlank(message = "email 不能为空")
    @Email(message = "email 格式不合法")
    private String email;

    @NotBlank(message = "displayName 不能为空")
    @Size(max = 64, message = "displayName 长度不能超过 64")
    private String displayName;

    /** 初始密码(明文)—— 后端 BCrypt 加密。至少 8 位,最大 64。 */
    @NotBlank(message = "initialPassword 不能为空")
    @Size(min = 8, max = 64, message = "密码长度 8-64")
    private String initialPassword;

    /** 1=启用 / 0=禁用;不传默认 1。 */
    private Integer status;
}
