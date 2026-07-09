package com.imawx.mcp.gateway.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 当前账号信息 VO（对齐前端 {@code ImawxAccountInfo} 形状）。 */
@Data
public class McpUserInfoVO {

    private String id;
    private String username;
    private String displayName;
    private String email;
    private Integer status;
    private Boolean mustChangePassword;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    /** 角色列表（阶段 1 默认超管，阶段 2 引入 RBAC）。 */
    private List<String> roles;

    /** 按钮权限（阶段 1 空）。 */
    private List<String> buttons;
}
