package com.imawx.mcp.gateway.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;

/**
 * 客户端请求信息解析工具(2026-07-02 加)—— 从 {@link HttpServletRequest} 拿
 * {@code clientIp} 和 {@code userAgent} 写进 {@code mcp_call_log}。
 *
 * <p>IP 解析优先级:
 * <ol>
 *   <li>{@code X-Forwarded-For} header 取第一个 —— 反向代理(Nginx / 负载均衡)标准做法</li>
 *   <li>{@code X-Real-IP} header —— Nginx 默认加</li>
 *   <li>{@code request.getRemoteAddr()} —— 直接连接时 fallback</li>
 * </ol>
 *
 * <p>User-Agent 优先读标准 {@code User-Agent} header,再兼容部分客户端或代理写错的
 * {@code Use-Agent}/{@code UserAgent} 等变体。DB 列 VARCHAR(512) 自带截断。
 */
public final class ClientInfoUtil {

    private ClientInfoUtil() {}

    /**
     * 解析客户端 IP(优先取代理后的真实 IP)。
     *
     * @param request HTTP 请求对象(可为 null,此时返回 null)
     * @return IP 字符串,无法解析时返 null
     */
    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) return null;
        // X-Forwarded-For 形如 "client, proxy1, proxy2" —— 取第一个
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) return first;
        }
        // X-Real-IP 单值(Nginx)
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        // 直连 fallback
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? null : remote;
    }

    public static String resolveClientIp(HttpServletRequest request, List<String> trustedProxyCidrs) {
        if (request == null) {
            return null;
        }
        String remote = request.getRemoteAddr();
        if (!isTrustedProxy(remote, trustedProxyCidrs)) {
            return remote == null || remote.isBlank() ? null : remote.trim();
        }
        return resolveClientIp(request);
    }

    /**
     * 解析客户端 User-Agent(MCP 客户端标识溯源用)。
     *
     * @param request HTTP 请求对象
     * @return UA 字符串,无 header 时返 null
     */
    public static String resolveUserAgent(HttpServletRequest request) {
        if (request == null) return null;
        String ua = firstHeader(request,
                "User-Agent",
                "user-agent",
                "X-Original-User-Agent",
                "X-Forwarded-User-Agent",
                "Use-Agent",
                "UserAgent");
        return ua == null || ua.isBlank() ? null : clean(ua);
    }

    private static String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String clean(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 32 && c != 127) {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private static boolean isTrustedProxy(String remoteAddr, List<String> trustedProxyCidrs) {
        if (remoteAddr == null || remoteAddr.isBlank() || trustedProxyCidrs == null || trustedProxyCidrs.isEmpty()) {
            return false;
        }
        for (String cidr : trustedProxyCidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            try {
                if (new IpAddressMatcher(cidr.trim()).matches(remoteAddr.trim())) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                if (cidr.trim().equals(remoteAddr.trim())) {
                    return true;
                }
            }
        }
        return false;
    }
}
