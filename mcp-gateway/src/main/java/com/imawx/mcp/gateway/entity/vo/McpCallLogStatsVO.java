package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 调用日志聚合统计 VO —— 给前端 dashboard / 列表头部 stats 卡片用。
 *
 * <p>SQL 一次算完(SUM / AVG / MAX / MIN),零循环、零 N+1。
 *
 * <p>avgCostMs 是 BigDecimal 类型 —— MySQL AVG 返回 DECIMAL,前端读时按 toFixed 取整。
 * 若 SQL 没命中行(用户没有日志),avg/min/max 都是 null,前端显示 "—"。
 */
@Data
@Builder
public class McpCallLogStatsVO {

    private Long totalCount;
    private Long successCount;
    private Long failedCount;
    private Long timeoutCount;
    /** 平均耗时(毫秒),保留 2 位小数。 */
    private java.math.BigDecimal avgCostMs;
    private Integer maxCostMs;
    private Integer minCostMs;

    /**
     * 成功率百分比(0-100),由 totalCount / successCount 推算。
     * 前端直接渲染 "98.5%" —— 不需要再算一次。
     */
    public Double getSuccessRate() {
        if (totalCount == null || totalCount == 0) {
            return null;
        }
        long ok = successCount == null ? 0 : successCount;
        return Math.round(ok * 10000.0 / totalCount) / 100.0;
    }
}