package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 外部 MCP server 列表 VO，对齐前端 {@code ImawxMcpProxy} interface。
 *
 * <p>id 用 String 输出（避免 JS Number 精度丢失；DB 用 BIGINT 自增）。
 */
@Data
@Builder
public class McpBackendVO {

    private String id;
    private String backendId;
    /** 创建人 userId(2026-07-03 加 —— 拆 userId 隔离后只记创建者)。 */
    private Long createdBy;
    /** 最近修改人 userId(2026-07-03 加)。 */
    private Long updatedBy;
    private String serverName;
    private String transportType;
    private String endpoint;
    private String extraConfig;
    private String remark;
    /** 用户标签(2026-07-01 加)。DO 里存 JSON 字符串,VO 转 List<String> 给前端消费。 */
    private List<String> tags;
    private String status;
    private Integer enabled;
    private Integer healthInterval;
    private Integer failThreshold;
    private Integer failCount;
    private LocalDateTime lastCheckAt;
    private String lastError;
    private LocalDateTime lastSyncAt;
    private String lastSyncError;
    /** Tool 数量。仅给监控/统计使用，列表卡片不展示，避免和工具快照语义混淆。 */
    private Integer toolCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
