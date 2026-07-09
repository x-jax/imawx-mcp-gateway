package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.service.mcpproxy.McpCallLogService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 大屏监控 Controller(2026-07-02 加)——
 *
 * <p>对应前端 dashboard 大屏监控,5 个新端点(原有 summary/trend 保留):
 * <ul>
 *   <li>{@code GET /api/sys/monitor/dashboard/status-distribution} —— 状态分布环形图</li>
 *   <li>{@code GET /api/sys/monitor/dashboard/hourly-trend} —— 24h 分时热力图</li>
 *   <li>{@code GET /api/sys/monitor/dashboard/slow-requests?limit=10} —— 慢请求表</li>
 *   <li>{@code GET /api/sys/monitor/dashboard/backend-stats} —— backend 状态网格</li>
 *   <li>{@code GET /api/sys/monitor/dashboard/transport-cost-trend} —— 按 transport 分组的 24h 平均耗时折线图(2026-07-02 加)</li>
 * </ul>
 *
 * <p>账号隔离:全部从 session 拿 userId 强绑;不允许跨用户查询。
 */
@Slf4j
@RestController
@RequestMapping("/api/sys/monitor/dashboard")
@RequiredArgsConstructor
public class SysMonitorDashboardController {

    private final McpCallLogService callLogService;

    @GetMapping("/status-distribution")
    public R<List<Map<String, Object>>> statusDistribution(HttpSession session) {
        Long userId = currentUserId(session);
        return R.ok(callLogService.statusDistribution(userId, null, null));
    }

    @GetMapping("/hourly-trend")
    public R<List<Map<String, Object>>> hourlyTrend(HttpSession session) {
        Long userId = currentUserId(session);
        return R.ok(callLogService.hourlyTrend(userId, null, null));
    }

    @GetMapping("/slow-requests")
    public R<List<Map<String, Object>>> slowRequests(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int limit,
            HttpSession session) {
        Long userId = currentUserId(session);
        return R.ok(callLogService.slowRequests(userId, null, null, limit));
    }

    @GetMapping("/backend-stats")
    public R<List<Map<String, Object>>> backendStats(HttpSession session) {
        Long userId = currentUserId(session);
        return R.ok(callLogService.backendStats(userId, null, null));
    }

    /**
     * 按 transport 分组的 24h 平均耗时趋势(2026-07-02 加)—— dashboard
     * transport 折线图用。
     *
     * <p>返回稀疏数据:{transportType, hour, avgCostMs},前端按 transportType GROUP BY
     * 后补 0 拼 24h 完整序列展示。
     *
     * <p>{@code hours} 不传默认 24(走 service normalizeRange)。
     */
    @GetMapping("/transport-cost-trend")
    public R<List<Map<String, Object>>> transportCostTrend(HttpSession session) {
        Long userId = currentUserId(session);
        return R.ok(callLogService.transportCostTrend(userId, null, null));
    }

    private Long currentUserId(HttpSession session) {
        Object uid = session.getAttribute(SessionKeys.USER_ID);
        if (uid == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        return (Long) uid;
    }
}
