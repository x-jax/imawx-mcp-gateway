package com.imawx.mcp.gateway.controller;

import tools.jackson.core.type.TypeReference;
import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.ClientInfoUtil;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.entity.do_.McpSessionDO;
import com.imawx.mcp.gateway.service.auth.McpApiTokenService;
import com.imawx.mcp.gateway.service.mcpproxy.McpAggregateService;
import com.imawx.mcp.gateway.service.mcpproxy.McpSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 标准协议聚合路由。
 *
 * <p>路径 {@code POST /mcp} 按 MCP Streamable HTTP 协议实现，兼容 Claude Desktop /
 * Cursor / Postman MCP 客户端 / 任意 MCP SDK 客户端。
 *
 * <p>协议处理要点:
 * <ol>
 *   <li><b>协议握手强制</b> —— 首次调用必须是 {@code initialize},server 创
 *       session + 返 {@code Mcp-Session-Id} header。客户端再发
 *       {@code notifications/initialized} 把 state 转 ACTIVE</li>
 *   <li><b>Session 头</b> —— 后续所有请求必须带 {@code Mcp-Session-Id},
 *       server 校验 userId 一致 + 续期 expire_at</li>
 *   <li><b>Accept 协商</b> —— 接受 {@code application/json, text/event-stream},
 *       server 选一种响应。流式 tool call 走 SSE</li>
 *   <li><b>路径标准</b> —— {@code /mcp} 跟官方 SDK / MCP server 约定一致</li>
 *   <li><b>协议版本协商</b> —— 客户端 initialize 传 {@code protocolVersion},
 *       server 返相同(如支持),或回退到 server 默认版本</li>
 *   <li><b>notifications</b> —— server-sent 走 SSE channel(progress / logging)</li>
 * </ol>
 *
 * <p>鉴权:仍用 {@code Authorization: Bearer imwx_xxx} —— 标准 OAuth 2.0
 * bearer token 形式,Claude Desktop / Cursor 的 MCP 配置都支持自定义 headers。
 *
 * <p>实现流程:
 * <ol>
 *   <li>Bearer Token 鉴权(走 SecurityConfig 的 TokenAuthInterceptor)——
 *       userId/tokenId 写到 request attribute,这里直接读取</li>
 *   <li>解析 JSON-RPC 2.0(method / params / id / jsonrpc)</li>
 *   <li>按 method 路由:initialize 创 session,其他 method 走
 *       McpSessionService.getActive 校验</li>
 *   <li>响应:如果 client Accept 含 text/event-stream + method 是流式
 *       (stream_text/stream_logs) → SSE;否则 JSON</li>
 * </ol>
 *
 * <p>路径是 {@code /mcp} 而不是 {@code /api/mcp}：MCP 标准 server
 * 都用 {@code /mcp} 端点,客户端 SDK (Python / TS) 默认就是 {@code /mcp}。
 * 加 {@code /api} 前缀是 admin 域约定,标准 MCP 域不加。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpStandardController {

    /** 当前默认协议版本。 */
    public static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";

    public static final String SERVER_NAME = "imawx-mcp-gateway";
    public static final String SERVER_VERSION = "1.0.0";

    /** Session ID header。 */
    public static final String SESSION_HEADER = "Mcp-Session-Id";

    private final McpAggregateService aggregateService;
    private final McpSessionService sessionService;
    private final McpGatewayProperties properties;

    /**
     * 标准 MCP JSON-RPC 端点(POST /mcp)。
     *
     * <p>produces 声明 JSON + SSE 两种响应类型,让 Spring 按 client 的
     * Accept header 选对应 converter。
     */
    @PostMapping(value = "/mcp",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object handle(
            @RequestBody(required = false) String body,
            @RequestHeader(value = SESSION_HEADER, required = false) String mcpSessionId,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            HttpServletRequest request) {

        Long userId = currentUserId(request);
        Long tokenId = currentTokenId(request);

        if (body == null || body.isBlank()) {
            return jsonRpcError(null, -32600, "请求体不能为空");
        }
        Map<String, Object> req;
        try {
            req = JsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return jsonRpcError(null, -32700, "Parse error: " + e.getMessage());
        }
        if (req == null) {
            return jsonRpcError(null, -32600, "请求体必须是非空 JSON object");
        }
        Object id = req.get("id");
        String method = (String) req.get("method");
        Object params = req.get("params");
        if (method == null || method.isBlank()) {
            return jsonRpcError(id, -32600, "method 字段不能为空");
        }

        try {
            if ("initialize".equals(method)) {
                return handleInitialize(userId, tokenId, id, params);
            }

            if ("notifications/initialized".equals(method)) {
                if (mcpSessionId == null) {
                    return jsonRpcError(id, -32600, "缺少 Mcp-Session-Id header");
                }
                sessionService.markActive(mcpSessionId, userId);
                // notifications 类消息不需要 result 响应,2024-11-05 直接返 202 Accepted
                return ResponseEntity.accepted().build();
            }

            McpSessionDO session = sessionService.getActive(mcpSessionId, userId, method);

            switch (method) {
                case "ping" -> {
                    return jsonRpcResult(id, Map.of());
                }
                case "tools/list" -> {
                    Map<String, Object> result = Map.of("tools", aggregateService.listTools(userId, session.getTokenId()));
                    return jsonRpcResult(id, result);
                }
                case "tools/call" -> {
                    return handleToolsCall(id, params, session.getSessionId(),
                            userId, session.getTokenId(), acceptHeader,
                            clientIp(request),
                            ClientInfoUtil.resolveUserAgent(request));
                }
                case "shutdown" -> {
                    sessionService.close(mcpSessionId, userId);
                    return ResponseEntity.noContent().build();
                }
                default -> {
                    return jsonRpcError(id, -32601, "method not supported: " + method);
                }
            }
        } catch (BizException e) {
            return jsonRpcError(id, -32000, e.getMessage());
        } catch (Exception e) {
            log.error("[mcp-std] handle fail method={} userId={} err={}", method, userId, e.getMessage(), e);
            return jsonRpcError(id, -32000, "internal error: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/mcp", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> unsupportedMethod() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(errorBody(null, -32600, "标准 MCP 端点只支持 POST /mcp"));
    }

    /**
     * 处理 initialize —— 客户端发起握手。
     *
     * <p>返回能力声明 + serverInfo + 创 session,response header 带
     * {@code Mcp-Session-Id}。客户端拿到 session id 后发
     * {@code notifications/initialized} 确认。
     */
    private ResponseEntity<Map<String, Object>> handleInitialize(
            Long userId, Long tokenId, Object id, Object params) {
        String protocolVersion = DEFAULT_PROTOCOL_VERSION;
        String clientInfoJson = null;
        if (params instanceof Map<?, ?> p) {
            Object pv = p.get("protocolVersion");
            if (pv instanceof String s && !s.isBlank()) {
                protocolVersion = negotiateProtocol(s);
            }
            Object ci = p.get("clientInfo");
            if (ci != null) {
                String s = JsonUtil.toJson(ci);
                if (s != null && s.length() > 500) s = s.substring(0, 500);
                clientInfoJson = s;
            }
        }

        String sid = sessionService.create(userId, tokenId, protocolVersion, clientInfoJson);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", protocolVersion);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        Map<String, Object> toolsCap = new LinkedHashMap<>();
        toolsCap.put("listChanged", false);
        capabilities.put("tools", toolsCap);
        Map<String, Object> loggingCap = new LinkedHashMap<>();
        capabilities.put("logging", loggingCap);
        result.put("capabilities", capabilities);
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.put("serverInfo", serverInfo);

        return ResponseEntity.ok()
                .header(SESSION_HEADER, sid)
                .body(successBody(id, result));
    }

    /**
     * 协议版本协商。当前实现对客户端版本保持宽松，后续可按支持清单收紧。
     */
    private String negotiateProtocol(String requested) {
        return requested.isBlank() ? DEFAULT_PROTOCOL_VERSION : requested;
    }

    /**
     * tools/call —— 看 client 是否接受 SSE + toolName 是不是流式(stream_text/stream_logs),
     * 决定返 JSON 还是 SSE。
     */
    private Object handleToolsCall(Object id, Object params, String sessionId,
                                   Long userId, Long tokenId, String acceptHeader,
                                   String clientIp, String userAgent) {
        if (!(params instanceof Map<?, ?> p)) {
            return jsonRpcError(id, -32602, "params 必须是 object");
        }
        Object name = p.get("name");
        if (!(name instanceof String s) || s.isBlank()) {
            return jsonRpcError(id, -32602, "params.name 不能为空");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) p.get("arguments");

        boolean sseAccepted = acceptSse(acceptHeader);
        boolean streamTool = s != null && (s.contains("stream_") || s.contains("logs"));

        if (sseAccepted && streamTool) {
            return sseToolsCall(id, s, args, sessionId, userId, tokenId, clientIp, userAgent);
        }
        Map<String, Object> out = aggregateService.callTool(userId, tokenId, s, args, sessionId, clientIp, userAgent);
        return jsonRpcResult(id, out);
    }

    /**
     * 流式 tools/call —— SseEmitter 推 logging/progress notifications,
     * 最后推 result event + complete。
     *
     * <p>2025-06-18 标准:response 是 SSE stream,事件:
     * <ul>
     *   <li>event: message, data: {jsonrpc, method: notifications/message, params: {level, data, ...}}</li>
     *   <li>event: message, data: {jsonrpc, method: notifications/progress, params: {progress, total}}</li>
     *   <li>event: message, data: {jsonrpc, id, result: {...}} (final)</li>
     * </ul>
     */
    private SseEmitter sseToolsCall(Object id, String prefixedName,
                                    Map<String, Object> args, String sessionId, Long userId, Long tokenId,
                                    String clientIp, String userAgent) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable finish = () -> {
            if (done.compareAndSet(false, true)) {
                try { emitter.complete(); } catch (Exception ignore) {}
            }
        };

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> result = aggregateService.callToolStreaming(
                        userId, tokenId, prefixedName, args, sessionId,
                        clientIp, userAgent,
                        (level, data) -> {
                            try {
                                Map<String, Object> notification = new LinkedHashMap<>();
                                notification.put("jsonrpc", "2.0");
                                notification.put("method", "notifications/message");
                                Map<String, Object> notifParams = new LinkedHashMap<>();
                                notifParams.put("level", level);
                                notifParams.put("data", data);
                                notification.put("params", notifParams);
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(notification, MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });

                Map<String, Object> finalBody = successBody(id, result);
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(finalBody, MediaType.APPLICATION_JSON));
                finish.run();
            } catch (BizException e) {
                sendError(emitter, id, e.getMessage(), finish);
            } catch (Exception e) {
                log.error("[mcp-std] sse tools/call fail userId={} tool={} err={}",
                        userId, prefixedName, e.getMessage(), e);
                sendError(emitter, id, e.getMessage() == null ? "internal error" : e.getMessage(), finish);
            }
        });

        emitter.onTimeout(finish);
        emitter.onError(t -> finish.run());
        return emitter;
    }

    private void sendError(SseEmitter emitter, Object id, String msg, Runnable finish) {
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(errorBody(id, -32000, msg), MediaType.APPLICATION_JSON));
        } catch (Exception ignore) {
        }
        finish.run();
    }

    private Long currentUserId(HttpServletRequest request) {
        Object uid = request.getAttribute(SessionKeys.USER_ID);
        if (!(uid instanceof Number n)) {
            throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.UNAUTHORIZED,
                    "未通过 token 鉴权");
        }
        return n.longValue();
    }

    /**
     * 拿当前请求对应的 token id。标准 MCP 端点不允许 session-only 访问。
     */
    private Long currentTokenId(HttpServletRequest request) {
        Object tid = request.getAttribute(McpApiTokenService.TOKEN_ID);
        if (tid instanceof Number n) {
            return n.longValue();
        }
        throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.UNAUTHORIZED,
                "标准 MCP 端点必须使用 Bearer Token");
    }

    private boolean acceptSse(String accept) {
        return accept != null && accept.contains("text/event-stream");
    }

    private String clientIp(HttpServletRequest request) {
        return ClientInfoUtil.resolveClientIp(request, properties.getSecurity().getTrustedProxyCidrs());
    }

    private Map<String, Object> successBody(Object id, Map<String, Object> result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (id != null) body.put("id", id);
        body.put("result", result);
        return body;
    }

    private Map<String, Object> errorBody(Object id, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (id != null) body.put("id", id);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        body.put("error", err);
        return body;
    }

    private Map<String, Object> jsonRpcResult(Object id, Object result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (id != null) body.put("id", id);
        body.put("result", result);
        return body;
    }

    private Map<String, Object> jsonRpcError(Object id, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (id != null) body.put("id", id);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        body.put("error", err);
        return body;
    }
}
