package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户列表 VO(2026-07-03 加)—— admin 路径。
 *
 * <p>id 用 String 输出(避免 JS Number 精度丢失);不带 passwordHash / totpSecret 等敏感字段。
 */
@Data
@Builder
public class McpUserListVO {

    private String id;
    private String username;
    private String displayName;
    private String email;
    /** 1=启用 / 0=禁用。 */
    private Integer status;
    /** 是否系统管理员 —— 写死 userId==1,前端用这个禁 admin 行的"禁用"按钮。 */
    private Boolean isAdmin;
    /** 是否启用了 2FA。 */
    private Boolean totpEnabled;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
