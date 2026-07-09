package com.imawx.mcp.gateway.common.logging;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.config.TokenAuthInterceptor;
import com.imawx.mcp.gateway.common.util.ClientInfoUtil;
import com.imawx.mcp.gateway.common.util.TraceIdUtil;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.controller.sys.SysAuthController;
import com.imawx.mcp.gateway.service.audit.McpAccessLogService;
import com.imawx.mcp.gateway.service.audit.McpAccessLogService.AccessLogWriteRequest;
import com.imawx.mcp.gateway.service.auth.McpApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全局 HTTP 访问日志。
 *
 * <p>记录所有 HTTP 请求的 IP、URI、状态和耗时；不记录 query、body、cookie、Authorization 原文，
 * 避免把账号密码/TOTP/API token 打进应用日志。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final int MAX_UA_LEN = 512;

    private final McpAccessLogService accessLogService;
    private final McpGatewayProperties properties;

    public AccessLogFilter(McpAccessLogService accessLogService, McpGatewayProperties properties) {
        this.accessLogService = accessLogService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String traceId = firstNonBlank(request.getHeader("X-Request-Id"), request.getHeader("X-Trace-Id"));
        if (traceId == null) {
            traceId = TraceIdUtil.generate();
        }
        MDC.put(TraceIdUtil.MDC_KEY, traceId);
        MDC.put("requestId", traceId);

        Throwable thrown = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            thrown = e;
            throw e;
        } finally {
            long costMs = System.currentTimeMillis() - start;
            int status = response.getStatus();
            Object userId = request.getAttribute(SessionKeys.USER_ID);
            Object tokenId = request.getAttribute(McpApiTokenService.TOKEN_ID);
            HttpSession session = request.getSession(false);
            if (userId == null && session != null) {
                userId = session.getAttribute(SessionKeys.USER_ID);
            }
            Object userEmail = request.getAttribute(SessionKeys.EMAIL);
            Object tokenPrefix = request.getAttribute(TokenAuthInterceptor.TOKEN_PREFIX_ATTR);
            if (userEmail == null && session != null) {
                userEmail = session.getAttribute(SessionKeys.EMAIL);
            }
            if (userEmail == null) {
                userEmail = request.getAttribute(SysAuthController.ACCESS_LOG_ACCOUNT_ATTR);
            }
            String uri = request.getRequestURI();
            boolean hasQuery = request.getQueryString() != null && !request.getQueryString().isBlank();
            String ua = trim(ClientInfoUtil.resolveUserAgent(request), MAX_UA_LEN);
            String ip = ClientInfoUtil.resolveClientIp(request, properties.getSecurity().getTrustedProxyCidrs());
            boolean hasAuth = request.getHeader("Authorization") != null;
            String result = thrown == null && status < 400 ? "SUCCESS" : "FAILED";
            String method = request.getMethod();
            String sessionId = session == null ? null : session.getId();
            Long currentUserId = toLong(userId);
            Long currentTokenId = toLong(tokenId);

            if (thrown == null) {
                log.info("[access] ip={} uri={} method={} result={} status={} costMs={} query={} userAgent=\"{}\" userId={} tokenId={} authHeader={} trace={}",
                        ip, uri, method, result, status, costMs, hasQuery, ua, currentUserId, currentTokenId,
                        hasAuth, traceId);
            } else {
                log.warn("[access] ip={} uri={} method={} result={} status={} costMs={} query={} userAgent=\"{}\" userId={} tokenId={} authHeader={} trace={} thrown={}",
                        ip, uri, method, result, status, costMs, hasQuery, ua, currentUserId, currentTokenId,
                        hasAuth, traceId, thrown.toString());
            }
            accessLogService.enqueueLog(new AccessLogWriteRequest(
                    traceId,
                    ip,
                    method,
                    uri,
                    result,
                    status,
                    safeCostMs(costMs),
                    hasQuery,
                    ua,
                    currentUserId,
                    normalizeEmail(userEmail),
                    currentTokenId,
                    normalizeTokenPrefix(tokenPrefix),
                    sessionId,
                    hasAuth
            ));
            MDC.remove("requestId");
            TraceIdUtil.clear();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b == null || b.isBlank() ? null : b.trim();
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int safeCostMs(long costMs) {
        if (costMs > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0, costMs);
    }

    private static String normalizeEmail(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty() || v.length() > 128 || !v.contains("@")) {
            return null;
        }
        return v;
    }

    private static String normalizeTokenPrefix(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty() || v.length() > 16) {
            return null;
        }
        return v;
    }

    private static String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
