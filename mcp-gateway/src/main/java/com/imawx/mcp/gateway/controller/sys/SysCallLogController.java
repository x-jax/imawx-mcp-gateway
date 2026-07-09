package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.entity.dto.McpCallLogQueryDTO;
import com.imawx.mcp.gateway.entity.vo.McpCallLogStatsVO;
import com.imawx.mcp.gateway.entity.vo.McpCallLogVO;
import com.imawx.mcp.gateway.service.mcpproxy.McpCallLogService;
import com.imawx.mcp.gateway.service.mcpproxy.McpCallLogService.PageResult;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 调用日志查询 Controller(2026-07-01 加)。
 *
 * <p>三个端点:
 * <ul>
 *   <li>{@code GET /api/sys/call-logs} —— 分页查询,字段透传 {@link McpCallLogQueryDTO}</li>
 *   <li>{@code GET /api/sys/call-logs/stats} —— 聚合统计(今日/自定义时间段)</li>
 *   <li>{@code GET /api/sys/call-logs/{id}} —— 单条详情</li>
 * </ul>
 *
 * <p>账号隔离:所有端点从 session 拿 userId,SQL WHERE 强绑;不允许跨用户查询。
 */
@RestController
@RequestMapping("/api/sys/call-logs")
@RequiredArgsConstructor
public class SysCallLogController {

    private final McpCallLogService callLogService;

    /**
     * 分页查询调用日志。
     *
     * @param query  查询条件(时间范围 / 状态 / backend / tool / 分页)
     * @param session HTTP session
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<McpCallLogVO>> page(McpCallLogQueryDTO query, HttpSession session) {
        return R.ok(callLogService.page(currentUserId(session), query));
    }

    /**
     * 聚合统计。
     *
     * @param from   起始时间(可空,默认今天 0 点)
     * @param to     结束时间(可空,默认当前)
     * @param session HTTP session
     * @return 统计 VO(总数 / 成功 / 失败 / 状态分布 / 分时趋势 / 后端排名 / 慢请求)
     */
    @GetMapping("/stats")
    public R<McpCallLogStatsVO> stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            HttpSession session) {
        return R.ok(callLogService.stats(currentUserId(session), from, to));
    }

    /**
     * 单条调用日志详情。
     *
     * @param id     日志主键
     * @param session HTTP session
     * @return 详情 VO
     */
    @GetMapping("/{id}")
    public R<McpCallLogVO> detail(@PathVariable Long id, HttpSession session) {
        McpCallLogVO vo = callLogService.detail(currentUserId(session), id);
        if (vo == null) {
            throw new BizException(BizErrorCode.NOT_FOUND, "调用日志不存在或不属于当前用户");
        }
        return R.ok(vo);
    }

    private static Long currentUserId(HttpSession session) {
        Object uid = session.getAttribute(SessionKeys.USER_ID);
        if (uid == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        if (uid instanceof Long l) return l;
        if (uid instanceof Number n) return n.longValue();
        if (uid instanceof String s) return Long.parseLong(s);
        throw new BizException(BizErrorCode.INTERNAL_ERROR, "userId 类型异常: " + uid.getClass());
    }
}