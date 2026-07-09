package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API Token 列表 VO(对应前端 {@code ImawxApiToken},2026-07-01 加;2026-07-03 加授权字段)。
 *
 * <p><b>绝不含 {@code tokenHash} 字段</b> —— 列表展示时不返回 token 摘要,
 * 避免密文通过日志/HTTP 抓包被泄露。
 */
@Data
@Builder
public class McpApiTokenVO {

    private String id;
    private String name;
    private String tokenPrefix;
    private List<String> scopes;
    private List<String> ipWhitelist;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private String lastUsedIp;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;

    /**
     * 授权模式(2026-07-03 加)。0=全开放 / 1=严格。
     * 前端展示 + PUT 更新用。
     */
    private Integer restrictMode;

    /**
     * 授权可访问的 backend 列表(2026-07-03 加)—— backend_id 字符串别名集合。
     */
    private List<String> authorizedBackends;

    /**
     * 授权可调用的具体 tool 列表(2026-07-03 加)—— (backendId, toolName) 二元组集合。
     */
    private List<AuthorizedToolRef> authorizedTools;

    /**
     * tool 授权引用(2026-07-03 加)—— 前端编辑时直接传这种 DTO 格式。
     */
    @Data
    @Builder
    public static class AuthorizedToolRef {
        private String backendId;
        private String toolName;
    }
}
