package com.imawx.mcp.gateway.common.config;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.common.util.ClientInfoUtil;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.service.auth.McpApiTokenService;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 标准 MCP 端点 Bearer Token 鉴权拦截器。
 *
 * <p>优先级 <b>高于</b> {@link SecurityConfig.SessionAuthInterceptor} —— 先尝试用 token,
 * 标准 MCP 端点 {@code /mcp} / {@code /mcp/**} 只允许 Bearer Token；后台管理 API 使用浏览器 session。
 *
 * <p>工作流程:
 * <ol>
 *   <li>读 {@code Authorization: Bearer imwx_xxx} 头</li>
 *   <li>无 header → {@code /mcp} / {@code /mcp/**} 直接 401</li>
 *   <li>有 header 但格式不对 → 401(避免 "Authorization 头错就被当成已登录")</li>
 *   <li>token 解析失败 → 401</li>
 *   <li>token 有效 → 只写 request attribute,return true 放行</li>
 * </ol>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TokenAuthInterceptor implements HandlerInterceptor {

    private final McpApiTokenService tokenService;
    private final McpGatewayProperties properties;
    /** 用 new ObjectMapper() 不用注入 — 不依赖 Spring 容器,启动顺序无关。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static final String TOKEN_PREFIX_ATTR = "IMAWX_TOKEN_PREFIX";

    /** Authorization 头里的 {@code Bearer} 前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.isBlank()) {
            if (isMcpEndpoint(request)) {
                writeUnauthorized(response, "标准 MCP 端点必须使用 Bearer Token");
                return false;
            }
            return true;
        }
        // 有 Authorization 头但格式不对 → 401(防中间件混淆)
        if (!auth.startsWith(BEARER_PREFIX)) {
            log.warn("[token-auth] bad Authorization header format, length={}", auth.length());
            writeUnauthorized(response, "Authorization 头格式必须是 'Bearer <token>'");
            return false;
        }
        String token = auth.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            writeUnauthorized(response, "Authorization 头缺少 token");
            return false;
        }
        // 凭明文 token 找 user
        String remoteIp = ClientInfoUtil.resolveClientIp(request, properties.getSecurity().getTrustedProxyCidrs());
        McpApiTokenService.AuthResult authResult = tokenService.resolveAuth(token, remoteIp);
        if (authResult == null) {
            log.info("[token-auth] invalid token, ip={}", remoteIp);
            writeUnauthorized(response, "token 无效、过期或已撤销");
            return false;
        }
        request.setAttribute(SessionKeys.USER_ID, authResult.userId());
        request.setAttribute(SessionKeys.EMAIL, authResult.userEmail());
        request.setAttribute(McpApiTokenService.TOKEN_ID, authResult.tokenId());
        request.setAttribute(TOKEN_PREFIX_ATTR, authResult.tokenPrefix());
        request.setAttribute(McpApiTokenService.AUTH_VIA_TOKEN, Boolean.TRUE);

        log.debug("[token-auth] userId={} tokenId={} ip={} path={}",
                authResult.userId(), authResult.tokenId(), remoteIp, request.getRequestURI());
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(BizErrorCode.UNAUTHORIZED.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Void> body = R.fail(BizErrorCode.UNAUTHORIZED.getCode(), message);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /**
     * 构造注入 — Spring 启动时由 SecurityConfig 字段注入时调用。
     * ObjectMapper 自己 new,避免启动时 bean 解析顺序问题。
     */
    public TokenAuthInterceptor(McpApiTokenService tokenService, McpGatewayProperties properties) {
        this.tokenService = tokenService;
        this.properties = properties;
    }

    private static boolean isMcpEndpoint(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return "/mcp".equals(uri) || uri.startsWith("/mcp/");
    }
}
