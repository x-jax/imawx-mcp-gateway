package com.imawx.mcp.gateway.common.config;

import com.imawx.mcp.gateway.service.monitor.LogFileWebSocketHandler;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class LogFileWebSocketConfig implements WebSocketConfigurer {

    private final LogFileWebSocketHandler logFileWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(logFileWebSocketHandler, "/ws/sys/log-files")
                .addInterceptors(new SessionHandshakeInterceptor());
    }

    private static final class SessionHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request,
                                       ServerHttpResponse response,
                                       WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                HttpSession session = servletRequest.getServletRequest().getSession(false);
                if (session != null && session.getAttribute(SessionKeys.USER_ID) != null) {
                    attributes.put(SessionKeys.USER_ID, session.getAttribute(SessionKeys.USER_ID));
                    return true;
                }
            }
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Exception exception) {
            // no-op
        }
    }
}
