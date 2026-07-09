package com.imawx.mcp.gateway.entity.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调用日志分页查询 DTO(2026-07-01 加,前端 ArtSearchBar 透传的字段)。
 *
 * <p>全部字段可选 —— 后端 Service 层按非 null / 非空过滤条件。
 *
 * <p>{@code status} 是 {@code SUCCESS / FAILED / TIMEOUT} 之一,Service 层翻译成
 * {@code success} + {@code minCostMs} 后传给 mapper:
 * <ul>
 *   <li>SUCCESS → success=1 + minCostMs<timeout</li>
 *   <li>FAILED  → success=0</li>
 *   <li>TIMEOUT → success=1 + minCostMs>=timeout</li>
 * </ul>
 */
@Data
public class McpCallLogQueryDTO {

    /** 开始时间(包含)。 */
    private LocalDateTime startTime;

    /** 结束时间(包含)。 */
    private LocalDateTime endTime;

    /** Tool 名(模糊匹配)。 */
    private String toolName;

    /** 后端 mcp_backend.id(强类型,精确匹配)。 */
    private Long backendId;

    /**
     * 服务名(2026-07-06 加)—— JOIN mcp_backend.server_name 模糊匹配。
     *
     * <p>前后端命名统一用 {@code serverName},DTO/VO/API 契约一致,避免重命名层;
     * 之前未提供该过滤项,调用日志页没法按"哪个后端"筛选。
     */
    private String serverName;

    /** 用户邮箱(模糊匹配)。 */
    private String userEmail;

    /** API Token 前缀(模糊匹配)。 */
    private String tokenPrefix;

    /** SUCCESS / FAILED / TIMEOUT(可选)。 */
    private String status;

    /** 分页。 */
    private int pageNum = 1;
    private int pageSize = 20;
}
