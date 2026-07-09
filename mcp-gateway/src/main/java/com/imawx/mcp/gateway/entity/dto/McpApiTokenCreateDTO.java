package com.imawx.mcp.gateway.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建 API Token 入参 DTO。
 */
@Data
public class McpApiTokenCreateDTO {

    /**
     * 用户备注名(必填)。
     * 例:"我的笔记本"、"CI 脚本"、"GitHub Actions"。
     */
    @NotBlank(message = "name 不能为空")
    @Size(max = 64, message = "name 最长 64 字符")
    private String name;

    /**
     * 权限范围(可选)。
     *
     * <p>默认 {@code ["read","write"]}。当前阶段不强制按 scope 鉴权(scope 字段只是元数据);
     * 后续做 RBAC 时按这个字段判定权限。
     */
    private List<String> scopes;

    /**
     * IP 白名单(可选)。空 = 不限制。
     *
     * <p>支持单 IP 或 CIDR,例如 {@code 42.200.230.156} / {@code 192.168.31.0/24}。
     */
    private List<String> ipWhitelist;

    /**
     * 过期时间(可选)。null = 永不过期。
     */
    private LocalDateTime expiresAt;

    /**
     * 授权模式。
     * <p>0=全开放，不创建任何授权记录，默认可调任何 backend/tool
     * <br>1=严格(必须配 {@link #authorizedBackends} 或 {@link #authorizedTools},否则无权限)
     * <br>null 不传 → 默认 1(新 token 默认严格)
     */
    private Integer restrictMode;

    /**
     * 授权可访问的 backend 列表(2026-07-03 加)。
     *
     * <p>格式:["bk_1782988191905", "bk_xxx"] —— backend_id 字符串别名。
     * 只在 {@link #restrictMode}=1 时生效。
     *
     * <p>空列表 = 没有 backend 级授权(只有 tool 级授权生效)。
     * 跟 {@link #authorizedTools} 是 OR 关系 —— 任一列表命中即通过。
     */
    private List<String> authorizedBackends;

    /**
     * 授权可调用的具体 tool 列表(2026-07-03 加)。
     *
     * <p>DTO 端用内嵌对象数组(更直观):
     * {@code [{"backendId": "bk_xxx", "toolName": "stream_text"}, ...]}
     * 服务端写入 mcp_token_tool_authorization 表时展开 (token_id, backend_id, tool_name) 三元组。
     */
    private List<AuthorizedToolRef> authorizedTools;

    /**
     * tool 授权引用(2026-07-03 加)—— DTO 端的扁平结构,服务端转写为中间表行。
     */
    @Data
    public static class AuthorizedToolRef {
        /** backend_id 字符串别名(bk_xxx)。 */
        @NotBlank
        private String backendId;
        /** tool 名称(不包含 method 前缀 —— 原始 tool name)。 */
        @NotBlank
        private String toolName;
    }
}
