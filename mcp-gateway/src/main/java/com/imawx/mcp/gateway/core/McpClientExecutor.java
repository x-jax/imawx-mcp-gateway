package com.imawx.mcp.gateway.core;

import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 模板：执行一段需要 MCP 客户端的逻辑，统一负责生命周期。
 *
 * <p>用 {@link #withClient(McpBackendDO, McpAction)} 消除 3 处 service 里的
 * "创建 client → initialize → try/finally close" 重复代码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpClientExecutor {

    private final McpClientFactory factory;

    /**
     * 创建客户端 → initialize → 执行 action → 关闭（异常也保证关闭）。
     *
     * <p>2026-07-02 改:返 {@link Outcome} 而不只是 {@code T} —— 因为写日志需要
     * outboundSessionId（反射从 client.transport 拿，调用方拿到后写 mcp_call_log）。
     * 业务结果放 {@code result()}。
     */
    public <T> Outcome<T> withClient(McpBackendDO backend, McpAction<T> action) {
        McpSyncClient client = null;
        T result;
        String outboundSessionId = null;
        try {
            client = factory.create(backend);
            client.initialize();
            // 2026-07-02 改:先 extract sessionId 再 close —— client.close() 会清掉 transport 内部的
            // activeSession,导致后续反射拿到 null 或 disposed 状态。但 markInitialized 是在 SDK 收到
            // initialize response 时同步触发的,这里 client.initialize() 返回后 sessionId 应该已就绪。
            outboundSessionId = McpSessionIdExtractor.extract(client);
            result = action.execute(client);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (client != null) {
                try {
                    client.close();
                    log.debug("[client-close] backendId={}", backend.getBackendId());
                } catch (Exception e) {
                    log.debug("client.close failed: {}", e.getMessage());
                }
            }
        }
        return new Outcome<>(result, outboundSessionId);
    }

    /**
     * 2026-07-02 加:流式 callTool 用 —— 返 {@link McpAsyncClient} + 接管 close 生命周期。
     * 跟 {@link #withClient} 区别:支持注册 logging / progress notification listener,
     * server 在 SSE 长连接上推的中间事件(实时 progress / logging)能被调用方处理。
     *
     * <p>每个 callTool 都开一个新 client(callTool 期间长连接独占),callTool 完(或异常)
     * 自动 close。{@code loggingHandler} / {@code progressHandler} 会被 server-sent
     * notification 异步触发(调用方应该在 handler 内做 thread-safe 的 SseEmitter.send)。
     *
     * <p>2026-07-02 改:返 {@link Outcome} 跟 withClient 对齐,带 outboundSessionId。
     */
    public <T> Outcome<T> withStreamingClient(
            McpBackendDO backend,
            Consumer<McpSchema.LoggingMessageNotification> loggingHandler,
            Consumer<McpSchema.ProgressNotification> progressHandler,
            McpAsyncAction<T> action
    ) {
        McpAsyncClient client = null;
        T result;
        String outboundSessionId = null;
        try {
            client = factory.createAsync(backend, loggingHandler, progressHandler);
            client.initialize().block();
            // 2026-07-02 改:先 extract sessionId 再 close(同 withClient 注释)
            outboundSessionId = McpSessionIdExtractor.extract(client);
            result = action.execute(client);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (client != null) {
                try {
                    // 2026-07-02:McpAsyncClient.close() 是 void(不是 Mono),跟 sync 一样
                    client.close();
                    log.debug("[client-close-async] backendId={}", backend.getBackendId());
                } catch (Exception e) {
                    log.debug("client.close async failed: {}", e.getMessage());
                }
            }
        }
        return new Outcome<>(result, outboundSessionId);
    }

    /**
     * 业务结果 + 被调方 session id（一个 call 算一次）。
     *
     * @param result            业务方法返回值（callTool result / listTools 等）
     * @param outboundSessionId MCP server 给的 session id，拿不到时 null
     */
    public record Outcome<T>(T result, String outboundSessionId) {
    }

    @FunctionalInterface
    public interface McpAction<T> {
        T execute(McpSyncClient client) throws Exception;
    }

    @FunctionalInterface
    public interface McpAsyncAction<T> {
        T execute(McpAsyncClient client) throws Exception;
    }
}