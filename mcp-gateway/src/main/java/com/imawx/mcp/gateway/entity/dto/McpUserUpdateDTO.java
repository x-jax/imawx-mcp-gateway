package com.imawx.mcp.gateway.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑用户 DTO(2026-07-03 加)—— admin 改别人 / 用户自己改 profile。
 *
 * <p>字段全部可选 —— null 表示不改。status 由 admin 改;非 admin 改自己时
 * {@code SysAuthController} 走 {@link #updateOwnProfile} 路径不会进 service update。
 */
@Data
public class McpUserUpdateDTO {

    @Size(max = 64, message = "displayName 长度不能超过 64")
    private String displayName;

    @Email(message = "email 格式不合法")
    private String email;

    /** 1=启用 / 0=禁用;仅 admin 路径可改。 */
    private Integer status;
}
