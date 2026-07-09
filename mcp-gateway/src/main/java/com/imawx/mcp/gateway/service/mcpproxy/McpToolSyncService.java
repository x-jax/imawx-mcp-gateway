package com.imawx.mcp.gateway.service.mcpproxy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.common.util.TraceIdUtil;
import com.imawx.mcp.gateway.core.McpClientExecutor;
import com.imawx.mcp.gateway.core.McpClientExecutor.Outcome;
import com.imawx.mcp.gateway.entity.do_.McpAggToolDO;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.entity.do_.McpBackendToolDO;
import com.imawx.mcp.gateway.entity.enums.ConnectionStatusEnum;
import com.imawx.mcp.gateway.entity.vo.McpInvokeResultVO;
import com.imawx.mcp.gateway.mapper.McpAggToolMapper;
import com.imawx.mcp.gateway.mapper.McpBackendToolMapper;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具同步服务：拉远端 Tool 列表 → 写 mcp_backend_tool（原始）+ mcp_agg_tool（聚合）
 * → 更新 mcp_backend.status / toolsSnapshot / lastSyncAt。
 *
 * <p>事务保证：删旧 + 插新 + 更新 backend 状态原子。失败时：
 * <ul>
 *   <li>写 {@code mcp_call_log}（success=0 + errorCode + errorMessage）</li>
 *   <li>更新 {@code mcp_backend.status=FAILED/DISCONNECTED} + failCount++ + lastSyncError</li>
 *   <li>返回 {@link SyncOutcome} 给 controller（不抛异常 — 同步失败是业务结果，不阻断 UI）</li>
 * </ul>
 *
 * <p>2026-07-02 改:每次写 call_log 都带完整入参/出参/transport/双向 session/流式日志合并,
 * 让日志页能反查"是谁调的工具、调了什么、谁给的回应、耗时多少"全套信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolSyncService {

    /** 同步结果截断长度（避免 tools_snapshot 字段超长）。 */
    private static final int SNAPSHOT_MAX = 64 * 1024;
    private static final int ERROR_MAX = 1024;
    private static final int RESULT_JSON_MAX = 256 * 1024;
    private static final int STREAM_LOGS_JSON_MAX = 256 * 1024;

    private final McpBackendService backendService;
    private final McpClientExecutor executor;
    private final McpBackendToolMapper backendToolMapper;
    private final McpAggToolMapper aggToolMapper;
    private final McpProviderRegistry providerRegistry;
    /**
     * 2026-07-02 改:写日志从本 service 私有方法抽到 {@link McpCallLogService#writeLog},
     * 让聚合路由 {@link McpAggregateService#callTool} 也能复用同一份写入逻辑
     * (避免两处分别实现 INSERT,字段定义 drift)。
     */
    private final McpCallLogService callLogService;

    @Transactional
    public SyncOutcome sync(Long userId, Long backendId, String inboundSessionId,
                             String clientIp, String userAgent) {
        long t0 = System.currentTimeMillis();
        String traceId = TraceIdUtil.currentOrNew();
        // 2026-07-03 改:getOwned(userId, id) → getById(id) —— 不再 userId 强绑;
        // userId 仍保留,只用于写 call_log("谁触发了这次同步")的审计
        McpBackendDO backend = backendService.getById(backendId);
        if (backend.getEnabled() == null || backend.getEnabled() != 1) {
            throw new BizException(BizErrorCode.BACKEND_DISABLED, "外部 MCP server 已停用");
        }

        try {
            Outcome<List<McpSchema.Tool>> outcome = executor.withClient(
                    backend, c -> c.listTools().tools());
            List<McpSchema.Tool> tools = outcome.result();
            int count = tools == null ? 0 : tools.size();
            persistTools(backend.getId(), tools);
            String snapshot = truncate(JsonUtil.toJson(tools), SNAPSHOT_MAX);
            updateBackendOk(backend.getId(), count, snapshot, LocalDateTime.now());
            int cost = (int) (System.currentTimeMillis() - t0);
            // sync 没有 tool 入参 → argumentsJson=null,resultJson 是 tools_snapshot
            // 2026-07-02 改:tool_name 存 method(tools/list),不再用 null 占位
            callLogService.writeLog(traceId, userId, backend, "tools/list",
                    backend.getTransportType(), inboundSessionId, outcome.outboundSessionId(),
                    null, snapshot, null,
                    true, null, null, cost,
                    clientIp, userAgent);

            log.info("[tool-sync] ok trace={} backendId={} userId={} toolCount={} costMs={} inboundSid={} outboundSid={}",
                    traceId, backend.getBackendId(), userId, count, cost,
                    inboundSessionId, outcome.outboundSessionId());
            return new SyncOutcome(true, count, null, null);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            int cost = (int) (System.currentTimeMillis() - t0);
            String msg = truncate(e.getMessage(), ERROR_MAX);
            updateBackendFail(backend, msg, LocalDateTime.now());
            // 失败时 outboundSessionId 可能为 null(initialize 没成功),仍然写日志
            callLogService.writeLog(traceId, userId, backend, "tools/list",
                    backend.getTransportType(), inboundSessionId, null,
                    null, null, null,
                    false, "MCP_REMOTE_ERROR", msg, cost,
                    clientIp, userAgent);
            log.error("[tool-sync] fail trace={} backendId={} userId={} costMs={} inboundSid={} err={}",
                    traceId, backend.getBackendId(), userId, cost, inboundSessionId, e.getMessage());
            return new SyncOutcome(false, 0, "MCP_REMOTE_ERROR", msg);
        }
    }

    /**
     * 读已同步的远端工具列表(从 mcp_backend_tool)。
     *
     * <p>2026-07-03 改:userId 参数移除 —— backend 不再按 user 隔离,直接按 id 查。
     */
    public List<McpBackendToolDO> listTools(Long backendId) {
        backendService.getById(backendId);
        return backendToolMapper.selectByBackendId(backendId);
    }

    /**
     * 2026-07-02 加:流式测试单个 tool —— 返 SseEmitter,server-sent logging/progress
     * notification 通过 SSE 实时推给前端,callTool final result 单独推一个 event。
     *
     * <p>跟 {@link #testTool} 区别:普通版是"等所有 chunk 完一次性返"(100 个 chunk × 5 秒
     * = 8 分钟,会超时),流式版是"边收边推"(server 推一个 chunk → 前端立刻收到一个 SSE event)。
     *
     * <p>实现:用 McpClientFactory.createAsync 注册 logging/progress listener,
     * listener 内部用 SseEmitter.send 把 notification 推给前端。callTool 仍然 block
     * 等 final result(因为 Mono 在 server 返 result 时才 complete),期间所有 notification
     * 通过 SseEmitter 实时推。lambda 拿到 CallToolResult 后转 VO + 推 result event。
     *
     * <p>2026-07-02 改:写一条合并 log 把所有 logging/progress 累积到 {@code stream_logs_json},
     * 跟 final result 一起存 —— 而不是分多条 log(用户反馈"8 条 logging + 1 条 result 不该是 9 行")。
     */
    public SseEmitter testToolStream(
            Long userId, Long backendId, String toolName, Map<String, Object> arguments,
            String inboundSessionId, String clientIp, String userAgent) {
        long t0 = System.currentTimeMillis();
        String traceId = TraceIdUtil.currentOrNew();
        // 2026-07-03 改:getOwned → getById;userId 仍保留用于写 call_log 审计
        McpBackendDO backend = backendService.getById(backendId);
        if (backend.getEnabled() == null || backend.getEnabled() != 1) {
            throw new BizException(BizErrorCode.BACKEND_DISABLED, "外部 MCP server 已停用");
        }

        // SseEmitter 默认 30 秒超时 —— 调成 30 分钟(callTool 可能跑很久,如 trigger-long-running-operation)
        SseEmitter emitter =
                new SseEmitter(30L * 60 * 1000);

        if (providerRegistry.find(backend.getTransportType()).isPresent()) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    McpInvokeResultVO vo = testTool(userId, backendId, toolName, arguments,
                            inboundSessionId, clientIp, userAgent);
                    emitter.send(SseEmitter.event().name("result").data(vo));
                    emitter.complete();
                } catch (Exception e) {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(Map.of("message", e.getMessage() == null ? "未知错误" : e.getMessage())));
                    } catch (Exception ignored) {
                    }
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        }

        String argumentsJson = arguments == null ? "{}" : JsonUtil.toJson(arguments);
        log.info("[tool-call-stream] start trace={} backendId={} toolName={} argBytes={} inboundSid={}",
                traceId, backend.getBackendId(), toolName, argumentsJson.length(), inboundSessionId);

        // 流式事件累积 —— 写 log 时合并到 stream_logs_json
        // List<Map> 同步访问(single test call),SseEmitter 顺序推送保证 FIFO
        List<Map<String, Object>> streamEvents =
                java.util.Collections.synchronizedList(new ArrayList<>());

        // 2026-07-02 改:tool_name 存 "method: tool" 格式
        String storedToolName = "tools/call:" + toolName;

        // 异步执行 —— 不能在 controller 线程里 block 30 分钟
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            McpSchema.CallToolResult result;
            String outboundSessionId;
            try {
                Outcome<McpSchema.CallToolResult> outcome = executor.withStreamingClient(
                        backend,
                        // logging notification handler —— server 推一个 chunk → 立刻 emit + 累积到 streamEvents
                        n -> {
                            try {
                                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                                payload.put("type", "logging");
                                payload.put("level", n.level());
                                if (n.logger() != null) payload.put("logger", n.logger());
                                payload.put("data", n.data());
                                payload.put("ts", System.currentTimeMillis());
                                streamEvents.add(payload);
                                emitter.send(SseEmitter.event()
                                        .name("logging")
                                        .data(payload));
                            } catch (Exception ignored) {
                            }
                        },
                        // progress notification handler
                        n -> {
                            try {
                                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                                payload.put("type", "progress");
                                payload.put("progress", n.progress());
                                if (n.total() != null) payload.put("total", n.total());
                                if (n.message() != null) payload.put("message", n.message());
                                payload.put("ts", System.currentTimeMillis());
                                streamEvents.add(payload);
                                emitter.send(SseEmitter.event()
                                        .name("progress")
                                        .data(payload));
                            } catch (Exception ignored) {
                            }
                        },
                        // 真正调 callTool —— .block() 等 server 返 final result
                        c -> c.callTool(
                                new McpSchema.CallToolRequest(toolName, arguments))
                                .block()
                );
                result = outcome.result();
                outboundSessionId = outcome.outboundSessionId();
            } catch (BizException e) {
                log.warn("[tool-call-stream] biz-fail trace={} errCode={} msg={}", traceId, e.getErrorCode(), e.getMessage());
                int cost = (int) (System.currentTimeMillis() - t0);
                callLogService.writeLog(traceId, userId, backend, storedToolName,
                        backend.getTransportType(), inboundSessionId, null,
                        argumentsJson, null, serializeStreamEvents(streamEvents),
                        false, e.getErrorCode().name(), e.getMessage(), cost,
                        clientIp, userAgent);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", e.getMessage())));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
                return;
            } catch (Exception e) {
                log.error("[tool-call-stream] fail trace={} err={}", traceId, e.getMessage(), e);
                int cost = (int) (System.currentTimeMillis() - t0);
                String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                if (msg.length() > ERROR_MAX) msg = msg.substring(0, ERROR_MAX);
                callLogService.writeLog(traceId, userId, backend, storedToolName,
                        backend.getTransportType(), inboundSessionId, null,
                        argumentsJson, null, serializeStreamEvents(streamEvents),
                        false, "MCP_REMOTE_ERROR", msg, cost,
                        clientIp, userAgent);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", msg)));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
                return;
            }

            // 成功 —— 写日志(合并 stream events)+ 推 result event + 完结 SSE
            int cost = (int) (System.currentTimeMillis() - t0);

            // 2026-07-03 改:result.isError() 业务错误映射到 success=0(跟 testTool / aggregate 对齐)。
            // 流式场景下 server 可能一边推 logging/progress,一边 final result 返 isError=true
            // (例如 stream_text 处理到一半 server 报错)—— 旧实现写死 success=true 会让 dashboard
            // 误以为流式调用是干净的,排查时找不出真正的根因。
            boolean transportOk = true;
            String errorCode = null;
            String errorMessage = null;
            boolean bizError = Boolean.TRUE.equals(result.isError());
            if (bizError) {
                transportOk = false;
                errorCode = "MCP_TOOL_BUSINESS_ERROR";
                errorMessage = extractFirstTextContent(result.content());
                if (errorMessage == null || errorMessage.isBlank()) {
                    errorMessage = "tool returned isError=true";
                }
                errorMessage = truncate(errorMessage, ERROR_MAX);
            }

            String resultJson = JsonUtil.toJson(toResultVO(result));
            callLogService.writeLog(traceId, userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, outboundSessionId,
                    argumentsJson, truncate(resultJson, RESULT_JSON_MAX),
                    serializeStreamEvents(streamEvents),
                    transportOk, errorCode, errorMessage, cost,
                    clientIp, userAgent);
            log.info("[tool-call-stream] ok trace={} backendId={} toolName={} costMs={} events={} inboundSid={} outboundSid={} isError={}",
                    traceId, backend.getBackendId(), toolName, cost,
                    streamEvents.size(), inboundSessionId, outboundSessionId, bizError);

            try {
                emitter.send(SseEmitter.event().name("result").data(toResultVO(result)));
                emitter.complete();
            } catch (Exception e) {
                log.error("[tool-call-stream] send result failed: {}", e.getMessage());
            }
        });

        return emitter;
    }

    public com.imawx.mcp.gateway.entity.vo.McpInvokeResultVO testTool(
            Long userId, Long backendId, String toolName, Map<String, Object> arguments,
            String inboundSessionId, String clientIp, String userAgent) {
        long t0 = System.currentTimeMillis();
        String traceId = TraceIdUtil.currentOrNew();
        // 2026-07-03 改:getOwned → getById;userId 仍保留用于写 call_log 审计
        McpBackendDO backend = backendService.getById(backendId);
        if (backend.getEnabled() == null || backend.getEnabled() != 1) {
            throw new BizException(BizErrorCode.BACKEND_DISABLED, "外部 MCP server 已停用");
        }

        String argumentsJson = arguments == null ? "{}" : JsonUtil.toJson(arguments);
        log.debug("[tool-call] calling trace={} backendId={} toolName={} argBytes={} inboundSid={}",
                traceId, backend.getBackendId(), toolName, argumentsJson.length(), inboundSessionId);
        // 2026-07-02 改:tool_name 存 "method: tool" 格式
        String storedToolName = "tools/call:" + toolName;
        var provider = providerRegistry.find(backend.getTransportType());
        if (provider.isPresent()) {
            return testProviderTool(provider.get(), backend, userId, toolName, arguments,
                    argumentsJson, storedToolName, inboundSessionId, clientIp, userAgent, traceId, t0);
        }
        try {
            Outcome<McpSchema.CallToolResult> outcome = executor.withClient(
                    backend, c -> c.callTool(
                            new McpSchema.CallToolRequest(toolName, arguments)));
            McpSchema.CallToolResult result = outcome.result();
            log.debug("[tool-call] got trace={} contentSize={} isError={}",
                    traceId,
                    result.content() == null ? -1 : result.content().size(),
                    result.isError());
            int cost = (int) (System.currentTimeMillis() - t0);

            // 2026-07-03 改:result.isError() 是 MCP 协议层的"业务错误"(如 SQL 执行失败),
            // 必须映射到 mcp_call_log.success=0 —— 原实现写死 true,统计口径失真。
            // 跟 McpAggregateService.callTool 的 bug 修复对齐(caa971f Bug D 漏改这里)。
            boolean transportOk = true;
            String errorCode = null;
            String errorMessage = null;
            boolean bizError = Boolean.TRUE.equals(result.isError());
            if (bizError) {
                transportOk = false;
                errorCode = "MCP_TOOL_BUSINESS_ERROR";
                errorMessage = extractFirstTextContent(result.content());
                if (errorMessage == null || errorMessage.isBlank()) {
                    errorMessage = "tool returned isError=true";
                }
                errorMessage = truncate(errorMessage, ERROR_MAX);
            }

            McpInvokeResultVO vo = toResultVO(result);
            String resultJson = truncate(JsonUtil.toJson(vo), RESULT_JSON_MAX);
            callLogService.writeLog(traceId, userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, outcome.outboundSessionId(),
                    argumentsJson, resultJson, null,
                    transportOk, errorCode, errorMessage, cost,
                    clientIp, userAgent);
            log.info("[tool-call] ok trace={} backendId={} toolName={} userId={} costMs={} inboundSid={} outboundSid={} isError={}",
                    traceId, backend.getBackendId(), toolName, userId, cost,
                    inboundSessionId, outcome.outboundSessionId(), bizError);
            return vo;
        } catch (Exception e) {
            int cost = (int) (System.currentTimeMillis() - t0);
            String msg = truncate(e.getMessage(), ERROR_MAX);
            callLogService.writeLog(traceId, userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, null,
                    argumentsJson, null, null,
                    false, "MCP_REMOTE_ERROR", msg, cost,
                    clientIp, userAgent);
            log.error("[tool-call] fail trace={} backendId={} toolName={} userId={} costMs={} err={}",
                    traceId, backend.getBackendId(), toolName, userId, cost, e.getMessage());
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR,
                    "工具调用失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private McpInvokeResultVO testProviderTool(McpProvider provider,
                                               McpBackendDO backend,
                                               Long userId,
                                               String toolName,
                                               Map<String, Object> arguments,
                                               String argumentsJson,
                                               String storedToolName,
                                               String inboundSessionId,
                                               String clientIp,
                                               String userAgent,
                                               String traceId,
                                               long startedAtMs) {
        try {
            boolean toolExists = provider.listTools(backend.getBackendId()).stream()
                    .anyMatch(t -> t.name().equals(toolName));
            if (!toolExists) {
                throw new BizException(BizErrorCode.NOT_FOUND,
                        "内置 provider tool 不存在: " + backend.getBackendId() + "__" + toolName);
            }
            McpProviderCallResult result = provider.callTool(new McpProviderCallRequest(
                    userId, null, backend.getTransportType(), backend.getBackendId(), toolName,
                    arguments == null ? Map.of() : arguments, inboundSessionId, clientIp, userAgent));
            int cost = (int) (System.currentTimeMillis() - startedAtMs);
            McpInvokeResultVO vo = toProviderResultVO(result);
            String resultJson = truncate(JsonUtil.toJson(vo), RESULT_JSON_MAX);
            callLogService.writeLog(traceId, userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, null,
                    argumentsJson, resultJson, null,
                    !result.isError(), result.isError() ? "PROVIDER_TOOL_ERROR" : null,
                    result.isError() ? "provider returned isError=true" : null,
                    cost, clientIp, userAgent);
            log.info("[tool-call] provider-ok trace={} backendId={} toolName={} userId={} costMs={} inboundSid={} isError={}",
                    traceId, backend.getBackendId(), toolName, userId, cost, inboundSessionId, result.isError());
            return vo;
        } catch (BizException e) {
            int cost = (int) (System.currentTimeMillis() - startedAtMs);
            callLogService.writeLog(traceId, userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, null,
                    argumentsJson, null, null,
                    false, e.getErrorCode().name(), e.getMessage(), cost,
                    clientIp, userAgent);
            log.warn("[tool-call] provider-biz-fail trace={} backendId={} toolName={} userId={} costMs={} code={} msg={}",
                    traceId, backend.getBackendId(), toolName, userId, cost, e.getErrorCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            int cost = (int) (System.currentTimeMillis() - startedAtMs);
            String msg = truncate(e.getMessage() == null ? "未知错误" : e.getMessage(), ERROR_MAX);
            callLogService.writeLog(traceId, userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, null,
                    argumentsJson, null, null,
                    false, "PROVIDER_MCP_ERROR", msg, cost,
                    clientIp, userAgent);
            log.error("[tool-call] provider-fail trace={} backendId={} toolName={} userId={} costMs={} err={}",
                    traceId, backend.getBackendId(), toolName, userId, cost, msg);
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "内置 provider 工具调用失败: " + msg);
        }
    }

    /** McpSchema.CallToolResult → VO(content blocks + isError + structuredContent)。 */
    private static McpInvokeResultVO toResultVO(McpSchema.CallToolResult result) {
        List<McpInvokeResultVO.ContentBlock> blocks = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content c : result.content()) {
                blocks.add(toContentBlock(c));
            }
        }
        return McpInvokeResultVO.builder()
                .content(blocks)
                .isError(Boolean.TRUE.equals(result.isError()))
                .structuredContent(result.structuredContent())
                .build();
    }

    private static McpInvokeResultVO toProviderResultVO(McpProviderCallResult result) {
        List<McpInvokeResultVO.ContentBlock> blocks = new ArrayList<>();
        if (result.content() != null) {
            for (Map<String, Object> item : result.content()) {
                blocks.add(toProviderContentBlock(item));
            }
        }
        return McpInvokeResultVO.builder()
                .content(blocks)
                .isError(result.isError())
                .structuredContent(result.metadata())
                .build();
    }

    private static McpInvokeResultVO textResultVO(Object result) {
        return McpInvokeResultVO.builder()
                .content(List.of(McpInvokeResultVO.ContentBlock.builder()
                        .type("text")
                        .data(JsonUtil.toJson(result))
                        .build()))
                .isError(false)
                .structuredContent(result)
                .build();
    }

    private static McpInvokeResultVO.ContentBlock toProviderContentBlock(Map<String, Object> item) {
        String type = item == null ? "text" : String.valueOf(item.getOrDefault("type", "text"));
        Object data = null;
        String mimeType = null;
        if (item != null) {
            data = item.containsKey("text") ? item.get("text")
                    : (item.containsKey("data") ? item.get("data") : item);
            Object mt = item.get("mimeType");
            if (mt != null) {
                mimeType = String.valueOf(mt);
            }
        }
        return McpInvokeResultVO.ContentBlock.builder()
                .type(type)
                .data(data)
                .mimeType(mimeType)
                .build();
    }

    /** McpSchema.Content → 前端 ContentBlock 转换(text / image / audio / resource / embeddedResource)。 */
    private static McpInvokeResultVO.ContentBlock toContentBlock(McpSchema.Content c) {
        String type = c.type();
        Object data = null;
        String mimeType = null;
        // 不同 content 类型取不同字段
        if (c instanceof McpSchema.TextContent tc) {
            data = tc.text();
        } else if (c instanceof McpSchema.ImageContent ic) {
            data = ic.data();
            mimeType = ic.mimeType();
        } else if (c instanceof McpSchema.AudioContent ac) {
            data = ac.data();
            mimeType = ac.mimeType();
        } else if (c instanceof McpSchema.EmbeddedResource er) {
            data = er.resource() == null ? null : er.resource().toString();
        }
        return McpInvokeResultVO.ContentBlock.builder()
                .type(type)
                .data(data)
                .mimeType(mimeType)
                .build();
    }

    /** 阶段 3 MCP Server tools/list 用：全量聚合工具。 */
    public List<McpAggToolDO> listAllAggTools() {
        return aggToolMapper.selectAllOrdered();
    }

    private void persistTools(Long backendId, List<McpSchema.Tool> tools) {
        // 1. 删旧
        backendToolMapper.delete(new LambdaQueryWrapper<McpBackendToolDO>()
                .eq(McpBackendToolDO::getBackendId, backendId));
        aggToolMapper.delete(new LambdaQueryWrapper<McpAggToolDO>()
                .eq(McpAggToolDO::getBackendId, backendId));
        if (tools == null || tools.isEmpty()) {
            return;
        }
        // 2. 批量插新（MyBatis-Plus 单条 insert；按用户硬约束"BaseMapper 只增删改"，批量仍走单条循环）
        for (McpSchema.Tool tool : tools) {
            String schema = JsonUtil.toJson(tool.inputSchema());

            McpBackendToolDO bt = new McpBackendToolDO();
            bt.setBackendId(backendId);
            bt.setToolName(tool.name());
            bt.setDescription(tool.description());
            bt.setInputSchema(schema);
            backendToolMapper.insert(bt);

            McpAggToolDO at = new McpAggToolDO();
            at.setAggName(backendId + "__" + tool.name());
            at.setBackendId(backendId);
            at.setToolName(tool.name());
            at.setDescription(tool.description());
            at.setInputSchema(schema);
            aggToolMapper.insert(at);
        }
    }

    private void updateBackendOk(Long id, int toolCount, String toolsSnapshot, LocalDateTime now) {
        McpBackendDO u = new McpBackendDO();
        u.setStatus(ConnectionStatusEnum.CONNECTED.getCode());
        u.setLastSyncAt(now);
        u.setLastSyncError(null);
        u.setToolsSnapshot(toolsSnapshot);
        u.setFailCount(0);
        backendService.update(id, u);
    }

    private void updateBackendFail(McpBackendDO backend, String errorMsg, LocalDateTime now) {
        int newFailCount = (backend.getFailCount() == null ? 0 : backend.getFailCount()) + 1;
        int threshold = backend.getFailThreshold() == null ? 3 : backend.getFailThreshold();
        int status = newFailCount >= threshold
                ? ConnectionStatusEnum.FAILED.getCode()
                : ConnectionStatusEnum.DISCONNECTED.getCode();

        McpBackendDO u = new McpBackendDO();
        u.setStatus(status);
        u.setLastSyncAt(now);
        u.setLastSyncError(errorMsg);
        u.setFailCount(newFailCount);
        backendService.updateByWrapper(backend.getId(), u);
    }

    // 写日志抽到 McpCallLogService.writeLog(2026-07-02)—— 让聚合路由也能复用。
    // sync / testTool / testToolStream 三条路径全部调 callLogService.writeLog(...),字段定义
    // 跟 schema 保持唯一来源,避免后期新增字段时漏改其中一处。

    /**
     * 把累积的流式事件转 JSON 数组字符串(给 {@code stream_logs_json} 字段存)。
     *
     * <p>超长截断 —— MCP server 推 100 个 logging × 几 KB 内容可能超 MEDIUMTEXT 上限;
     * 截断后只保留前 N 字节,丢弃尾部 + 加 marker,前端能识别。
     */
    private String serializeStreamEvents(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        String json = JsonUtil.toJson(events);
        return truncate(json, STREAM_LOGS_JSON_MAX);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 抽取 content 列表里第一个 text content 的文本(2026-07-03 加)——
     * 业务 isError 时给 call_log.errorMessage 填一个一眼可见的根因,跟
     * {@code McpAggregateService.extractFirstTextContent} 同款实现(两处不互相依赖,
     * 避免抽到 base util 后 import 圈)。
     *
     * @param content MCP 协议层 content 列表
     * @return 第一个 text 的字符串;没有 text 时返 null
     */
    private static String extractFirstTextContent(List<McpSchema.Content> content) {
        if (content == null) return null;
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent tc) {
                return tc.text();
            }
        }
        return null;
    }

    public record SyncOutcome(boolean success, int toolCount, String errorCode, String errorMessage) {
    }
}
