package com.imawx.mcp.gateway.common.config;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Supplier;

/**
 * Spring Security + 自定义 Session/Token 鉴权拦截器配置。
 *
 * <p>Spring Security 全放行(只配 {@code permitAll()}),真正的鉴权由两个自定义拦截器负责:
 * <ol>
 *   <li>{@link TokenAuthInterceptor} —— 只保护标准 MCP 端点 {@code /mcp}</li>
 *   <li>{@link SessionAuthInterceptor} —— 保护后台管理 {@code /api/**}</li>
 * </ol>
 */
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** 标准 MCP 端点 Bearer Token 鉴权拦截器。 */
    @Autowired
    private TokenAuthInterceptor tokenAuthInterceptor;

    @Autowired
    private McpGatewayProperties properties;

    /**
     * 显式放行所有 URL(鉴权由自定义拦截器负责)。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        if (properties.getSecurity().getCsrf().isEnabled()) {
            http.csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                    .ignoringRequestMatchers(csrfIgnoredEndpointMatcher()));
        } else {
            http.csrf(AbstractHttpConfigurer::disable);
        }
        http.exceptionHandling(ex -> ex.accessDeniedHandler((request, response, accessDeniedException) -> {
            log.warn("[security-403] uri={} method={} remoteAddr={} xForwardedProto={} reason={}",
                    request.getRequestURI(), request.getMethod(), request.getRemoteAddr(),
                    request.getHeader("X-Forwarded-Proto"), accessDeniedException.getMessage());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            R<Void> body = R.fail(BizErrorCode.FORBIDDEN.getCode(), "Forbidden");
            new ObjectMapper().writeValue(response.getOutputStream(), body);
        }));
        return http.build();
    }

    private static RequestMatcher csrfIgnoredEndpointMatcher() {
        return request -> {
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
                uri = uri.substring(contextPath.length());
            }
            if ("/mcp".equals(uri) || uri.startsWith("/mcp/") || "/api/sys/auth/login".equals(uri)) {
                return true;
            }
            return "POST".equalsIgnoreCase(request.getMethod())
                    && uri.startsWith("/api/sys/mcp-proxy/")
                    && uri.endsWith("/test-stream")
                    && uri.contains("/tools/");
        };
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> httpsOnlyFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                if (properties.getSecurity().isRequireHttps() && !isHttpsRequest(request)) {
                    log.warn("[security-https-block] uri={} method={} remoteAddr={} xForwardedProto={} trustedCidrs={}",
                            request.getRequestURI(), request.getMethod(), request.getRemoteAddr(),
                            request.getHeader("X-Forwarded-Proto"), properties.getSecurity().getTrustedProxyCidrs());
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    R<Void> body = R.fail(BizErrorCode.FORBIDDEN.getCode(), "HTTPS required");
                    new ObjectMapper().writeValue(response.getOutputStream(), body);
                    return;
                }
                filterChain.doFilter(request, response);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    private boolean isHttpsRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null
                && isTrustedProxy(request.getRemoteAddr(), properties.getSecurity().getTrustedProxyCidrs())
                && "https".equalsIgnoreCase(forwardedProto.split(",", 2)[0].trim());
    }

    private static boolean isTrustedProxy(String remoteAddr, List<String> trustedCidrs) {
        if (remoteAddr == null || remoteAddr.isBlank() || trustedCidrs == null || trustedCidrs.isEmpty()) {
            return false;
        }
        for (String cidr : trustedCidrs) {
            if (cidr != null && !cidr.isBlank() && matchesCidr(remoteAddr, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCidr(String address, String cidr) {
        try {
            String[] parts = cidr.split("/", 2);
            InetAddress remote = InetAddress.getByName(address);
            InetAddress network = InetAddress.getByName(parts[0]);
            if (remote.getAddress().length != network.getAddress().length) {
                return false;
            }
            int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : network.getAddress().length * 8;
            if (bits < 0 || bits > network.getAddress().length * 8) {
                return false;
            }
            BigInteger remoteInt = new BigInteger(1, remote.getAddress());
            BigInteger networkInt = new BigInteger(1, network.getAddress());
            int totalBits = network.getAddress().length * 8;
            BigInteger mask = BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE)
                    .shiftRight(bits).not().and(BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE));
            return remoteInt.and(mask).equals(networkInt.and(mask));
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    /**
     * Cookie CSRF token 请求处理器，对齐 awx-base 的 AwxCookieCsrfTokenRequestHandler。
     *
     * <p>前端从 {@code XSRF-TOKEN} cookie 读取明文 token，并通过 {@code X-XSRF-TOKEN}
     * header 回传；header 存在时按明文 token 解析，兼容 axios 的 xsrf 机制。
     */
    private static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final XorCsrfTokenRequestAttributeHandler xor = new XorCsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();

        private SpaCsrfTokenRequestHandler() {
            this.xor.setCsrfRequestAttributeName(null);
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            this.xor.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(headerValue) ? this.plain : this.xor)
                    .resolveCsrfTokenValue(request, csrfToken);
        }
    }

    // ====================== 拦截器注册 ======================

    @Bean
    public SessionAuthInterceptor sessionAuthInterceptor() {
        return new SessionAuthInterceptor();
    }

    /**
     * 注册 MCP token 鉴权和后台 session 鉴权。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenAuthInterceptor)
                // 标准 MCP 端点走 Bearer Token 鉴权。
                .addPathPatterns("/mcp", "/mcp/**");
        registry.addInterceptor(sessionAuthInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/sys/auth/login",
                        "/api/sys/auth/csrf",
                        "/api/sys/auth/logout",
                        "/api/sys/constants",
                        "/api/admin/meta/**",
                        "/api/public/**"
                );
        // session interceptor 不拦 /mcp: 标准 MCP 端点是 token-only。
    }

    // ====================== 拦截器实现 ======================

    /**
     * Session 鉴权拦截器。未登录返 401 JSON。
     */
    public static class SessionAuthInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute(SessionKeys.USER_ID) == null) {
                writeUnauthorized(response);
                return false;
            }
            if (Boolean.TRUE.equals(session.getAttribute(SessionKeys.MUST_CHANGE_PASSWORD))
                    && !isPasswordChangeAllowedPath(request)) {
                writeForbidden(response);
                return false;
            }
            request.setAttribute(SessionKeys.USER_ID, session.getAttribute(SessionKeys.USER_ID));
            Object email = session.getAttribute(SessionKeys.EMAIL);
            if (email != null) {
                request.setAttribute(SessionKeys.EMAIL, email);
            }
            return true;
        }

        private boolean isPasswordChangeAllowedPath(HttpServletRequest request) {
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
                uri = uri.substring(contextPath.length());
            }
            String method = request.getMethod();
            return ("GET".equalsIgnoreCase(method) && "/api/sys/auth/me".equals(uri))
                    || ("PUT".equalsIgnoreCase(method) && "/api/sys/auth/me/password".equals(uri))
                    || "/api/sys/auth/logout".equals(uri)
                    || "/api/sys/auth/csrf".equals(uri);
        }

        private void writeUnauthorized(HttpServletResponse response) throws Exception {
            response.setStatus(BizErrorCode.UNAUTHORIZED.getHttpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            R<Void> body = R.fail(BizErrorCode.UNAUTHORIZED.getCode(), BizErrorCode.UNAUTHORIZED.getMessage());
            new ObjectMapper().writeValue(response.getOutputStream(), body);
        }

        private void writeForbidden(HttpServletResponse response) throws Exception {
            response.setStatus(BizErrorCode.PASSWORD_CHANGE_REQUIRED.getHttpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            R<Void> body = R.fail(BizErrorCode.PASSWORD_CHANGE_REQUIRED.getCode(),
                    BizErrorCode.PASSWORD_CHANGE_REQUIRED.getMessage());
            new ObjectMapper().writeValue(response.getOutputStream(), body);
        }
    }
}
