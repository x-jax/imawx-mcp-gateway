package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpCallLogDO;
import com.imawx.mcp.gateway.entity.vo.McpCallLogStatsVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code mcp_call_log} Mapper(2026-07-01 加查询能力)。
 *
 * <p>阶段 1 同步服务用 {@link #insert} / 走 BaseMapper 的能力;
 * 阶段 2 起加查询能力(分页 + 详情 + 统计),供前端调用日志页用。
 *
 * <p>查询 SQL 在 {@code McpCallLogMapper.xml} —— BaseMapper 只能增删改,
 * 复杂查询走 XML 是项目硬约束。
 */
public interface McpCallLogMapper extends BaseMapper<McpCallLogDO> {

    /** 分页查询列表(JOIN mcp_backend 拿 server_name)。 */
    List<com.imawx.mcp.gateway.entity.vo.McpCallLogVO> selectPageList(
            @Param("query") McpCallLogQuery query);

    /** 计数(对应 selectPageList 的 WHERE)。 */
    long countByQuery(@Param("query") McpCallLogQuery query);

    /** 单条详情(账号隔离:user_id 必须在 WHERE)。 */
    com.imawx.mcp.gateway.entity.vo.McpCallLogVO selectDetail(
            @Param("id") Long id,
            @Param("userId") Long userId);

    /** 聚合统计(今日调用数 / 成功率 / 平均耗时 / 超时数)。 */
    McpCallLogStatsVO selectStats(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("timeoutMs") int timeoutMs);

    // ===== Dashboard 大屏监控(2026-07-02 加)=====

    /**
     * 调用状态分布(2026-07-02 加)—— dashboard 环形图用。
     * 返 3 行 [{status: "SUCCESS/FAILED/TIMEOUT", cnt: N}]。
     */
    List<java.util.Map<String, Object>> selectStatusDistribution(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("timeoutMs") int timeoutMs);

    /**
     * 24h 分时调用(2026-07-02 加)—— dashboard 24h 热力图用。
     * 返 N 行 {hour: 0-23, invokeCount, errorCount}。
     */
    List<java.util.Map<String, Object>> selectHourlyTrend(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * Top 慢请求(2026-07-02 加)—— dashboard 慢请求表用,按 cost_ms DESC。
     */
    List<java.util.Map<String, Object>> selectSlowRequests(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit);

    /**
     * 各 backend 聚合(2026-07-02 加)—— dashboard backend 状态网格用。
     * LEFT JOIN mcp_call_log 按时间窗聚合。
     */
    List<java.util.Map<String, Object>> selectBackendStats(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 按 transport 分组的 24h 平均耗时趋势(2026-07-02 加)—— dashboard transport
     * 平均耗时折线图用。每行一个 (transportType, hour, avgCostMs);前端按
     * transportType GROUP BY 后补 0 拼 24 小时完整序列。
     */
    List<java.util.Map<String, Object>> selectTransportCostTrend(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 查询参数 holder(避免 MyBatis 用 Map<String,Object> 黑盒)。
     *
     * <p>字段语义跟 {@code McpCallLogQueryDTO} 一一对应 —— DTO 在 Controller 层接收,
     * 转换成这个 holder 传进 XML,XML 内部按 getter 访问字段。
     */
    record McpCallLogQuery(
            Long userId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String toolName,
            Long backendId,
            /** 服务名(2026-07-06 加)—— JOIN mcp_backend.server_name 模糊匹配 */
            String serverName,
            String userEmail,
            String tokenPrefix,
            /** 1成功 / 0失败;TIMEOUT 在 service 层拆出(按 costMs >= timeoutMs) */
            Integer success,
            Integer minCostMs,
            int offset,
            int size
    ) {
    }
}
