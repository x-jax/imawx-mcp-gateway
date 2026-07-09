package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.entity.dto.McpAccessLogQueryDTO;
import com.imawx.mcp.gateway.entity.vo.McpAccessLogVO;
import com.imawx.mcp.gateway.service.audit.McpAccessLogService;
import com.imawx.mcp.gateway.service.audit.McpAccessLogService.PageResult;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 请求访问日志查询 Controller。
 */
@RestController
@RequestMapping("/api/sys/access-logs")
@RequiredArgsConstructor
public class SysAccessLogController {

    private final McpAccessLogService accessLogService;

    @GetMapping
    public R<PageResult<McpAccessLogVO>> page(McpAccessLogQueryDTO query, HttpSession session) {
        return R.ok(accessLogService.page(currentUserId(session), query));
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
