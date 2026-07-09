package com.imawx.mcp.gateway.core;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpTransportSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 MCP SDK 客户端反射拿"被调方 session id"（{@code mcp-session-id}）。
 *
 * <h3>为什么需要反射</h3>
 * MCP SDK 2.0 的 {@code McpClientTransport} 内部维护 {@code McpTransportSession}，
 * server 在 {@code initialize} response header 里给的 {@code mcp-session-id}
 * 就存在那里。但 SDK 没暴露 getter 给 user —— {@code McpSyncClient.transport} 私有，
 * {@code McpTransportSession.sessionId()} 是 public 方法但 SDK 不返给我们 transport。
 *
 * <p>本类用反射读 {@code McpSyncClient} 的 {@code transport} 私有字段，
 * 再拿 {@code activeSession}（Streamable HTTP / SSE transport 都有），最后调
 * {@code McpTransportSession.sessionId().orElse(null)}。
 *
 * <h3>失败处理</h3>
 * 拿不到时返 null —— 不抛异常。日志记录一行 warn,不影响主流程。
 * 调用方写 {@code mcp_call_log.outbound_session_id} 时直接存 null。
 *
 * <h3>稳定吗</h3>
 * 不保证 SDK 升级后字段名不变。但 MCP 2.0 当前稳定,如果改了我们 catch 异常返 null 即可。
 * 长远来看应该提 PR 让 SDK 暴露 {@code McpClient.getSessionId()}。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * try (McpSyncClient client = factory.create(backend)) {
 *     client.initialize();
 *     String mcpSessionId = McpSessionIdExtractor.extract(client);
 *     ...
 * }
 * }</pre>
 */
public final class McpSessionIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(McpSessionIdExtractor.class);

    private McpSessionIdExtractor() {
    }

    /**
     * 从同步 client 拿 outbound session id。
     *
     * <p>MCP SDK 2.0 内部结构:{@code McpSyncClient} → {@code delegate: McpAsyncClient}
     * → {@code transport: McpClientTransport}(SSE 老 1.x transport 没有 activeSession;
     * Streamable HTTP 2.0 transport 有 activeSession + sessionId)。
     *
     * @param client 已 initialize 完成的 sync client
     * @return MCP server 给的 session id；拿不到返 null（不抛异常）
     */
    public static String extract(McpSyncClient client) {
        if (client == null) return null;
        // McpSyncClient.delegate → McpAsyncClient → transport
        McpAsyncClient delegate = readDelegate(client);
        if (delegate == null) return null;
        return doExtract(client.getClass(), extractTransport(delegate));
    }

    /**
     * 从异步 client 拿 outbound session id。
     */
    public static String extract(McpAsyncClient client) {
        if (client == null) return null;
        return doExtract(client.getClass(), extractTransport(client));
    }

    /**
     * 反射读 {@code McpSyncClient.delegate}(私有,字段类型 {@code McpAsyncClient})。
     */
    private static McpAsyncClient readDelegate(McpSyncClient syncClient) {
        try {
            Field f = findField(syncClient.getClass(), "delegate");
            if (f == null) {
                log.debug("[mcp-session-id] no 'delegate' field on McpSyncClient");
                return null;
            }
            f.setAccessible(true);
            return (McpAsyncClient) f.get(syncClient);
        } catch (Throwable e) {
            log.debug("[mcp-session-id] reflect delegate failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 反射读 {@code McpAsyncClient.transport}(私有,字段类型 {@code McpClientTransport})。
     */
    private static Object extractTransport(McpAsyncClient asyncClient) {
        try {
            Field f = findField(asyncClient.getClass(), "transport");
            if (f == null) {
                log.debug("[mcp-session-id] no 'transport' field on {}", asyncClient.getClass().getName());
                return null;
            }
            f.setAccessible(true);
            return f.get(asyncClient);
        } catch (Throwable e) {
            log.debug("[mcp-session-id] reflect transport failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 反射读 transport 的 {@code activeSession}（{@code AtomicReference<McpTransportSession>}），
     * 再调 {@code sessionId().orElse(null)}。
     *
     * <p>支持的 transport:
     * <ul>
     *   <li>{@code HttpClientStreamableHttpTransport} —— 有 {@code activeSession}(MCP 2.0 协议)</li>
     *   <li>{@code HttpClientSseClientTransport} —— 无 activeSession(MCP 1.x 老 SSE,用 endpoint 寻址,不分配 session id)</li>
     *   <li>{@code StdioClientTransport} —— 无 session 概念（stdio 是本地进程）</li>
     * </ul>
     */
    private static String doExtract(Class<?> clientClass, Object transport) {
        if (transport == null) return null;
        try {
            Field f = findField(transport.getClass(), "activeSession");
            if (f == null) {
                log.debug("[mcp-session-id] transport {} has no activeSession field (likely STDIO)",
                        transport.getClass().getSimpleName());
                return null;
            }
            f.setAccessible(true);
            Object ref = f.get(transport);
            if (!(ref instanceof AtomicReference<?> atomic)) {
                log.debug("[mcp-session-id] activeSession not AtomicReference on {}",
                        transport.getClass().getName());
                return null;
            }
            Object session = atomic.get();
            if (session == null) {
                log.debug("[mcp-session-id] activeSession is null on {} (server may not have assigned id)",
                        transport.getClass().getSimpleName());
                return null;
            }
            // McpTransportSession.sessionId() → Optional<String>
            if (session instanceof McpTransportSession<?> mts) {
                java.util.Optional<String> sid = mts.sessionId();
                if (sid.isEmpty()) {
                    log.debug("[mcp-session-id] sessionId() empty on {} (server didn't return mcp-session-id header)",
                            transport.getClass().getSimpleName());
                    return null;
                }
                String v = sid.get();
                log.debug("[mcp-session-id] extracted {} on {}", v, transport.getClass().getSimpleName());
                return v;
            }
            log.debug("[mcp-session-id] activeSession value type unexpected: {}",
                    session.getClass().getName());
            return null;
        } catch (Throwable e) {
            log.debug("[mcp-session-id] extract sessionId failed on {}: {}",
                    clientClass.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 递归查字段名(自身 → 父类)—— 因为 {@code transport} 字段在 {@code McpClient} 父类里,
     * {@code McpSyncClient} 自己没声明,直接 {@code getDeclaredField} 找不到。
     */
    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (f != null) return f;
            } catch (NoSuchFieldException ignored) {
                // 继续查父类
            }
        }
        return null;
    }
}