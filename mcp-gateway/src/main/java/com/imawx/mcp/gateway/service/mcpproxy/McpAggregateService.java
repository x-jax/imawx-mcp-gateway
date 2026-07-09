package com.imawx.mcp.gateway.service.mcpproxy;

import tools.jackson.core.type.TypeReference;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.common.util.TraceIdUtil;
import com.imawx.mcp.gateway.core.McpClientExecutor;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.entity.do_.McpBackendToolDO;
import com.imawx.mcp.gateway.entity.do_.McpToolOverrideDO;
import com.imawx.mcp.gateway.entity.vo.McpInvokeResultVO;
import com.imawx.mcp.gateway.mapper.McpBackendToolMapper;
import com.imawx.mcp.gateway.service.auth.McpApiTokenService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.McpTransportDescriptorService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderRegistry;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderServer;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP 协议聚合路由服务。
 *
 * <p>第三方客户端（CI / 脚本 / agent）只对接 gateway 的 {@code /mcp} 端点，
 * 不需要感知内部挂载了多少 backend。
 *
 * <p>Tool name 前缀策略：{@code <backendId>__<toolName>}。例如 {@code bk_xxx__echo}。
 * 双下划线分隔避免跟 backendId 自身的字符冲突（backendId 只允许 [a-z0-9_]）。
 * tools/list 返这种带前缀的 tool,tools/call 收到同样格式的 name 后解析转发。
 *
 * <p>能力声明：
 * <ul>
 *   <li>protocolVersion: "2024-11-05"（兼容当前 SDK 和主流 MCP client）</li>
 *   <li>capabilities.tools.listChanged: false（暂时不主动推 tools 变更,客户端轮询即可）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpAggregateService {

    /** tool name 分隔符：双下划线,backendId 字符集里只有 [a-z0-9_],不会冲突。 */
    public static final String TOOL_NAME_SEP = "__";

    /** 固定 protocolVersion,跟 SSE/Streamable HTTP transport 都兼容(SDK 2.0 默认都支持 2024-11-05)。 */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    private static final String SERVER_NAME = "imawx-mcp-gateway";
    private static final String SERVER_VERSION = "1.0.0";

    private final McpBackendService backendService;
    private final McpBackendToolMapper backendToolMapper;
    private final McpClientExecutor clientExecutor;
    /**
     * 聚合路由的 tools/call 写入同一张调用日志表，便于审计调用方、backend、tool 和入参出参。
     */
    private final McpCallLogService callLogService;
    /** Token 授权校验：严格模式下只返回/调用 token 被授权的 backend 和 tool。 */
    private final McpApiTokenService tokenService;
    /** 网关内置 HTTP API provider，如阿里云域名、OSS、Redis。 */
    private final McpProviderRegistry providerRegistry;
    private final McpToolOverrideService toolOverrideService;
    private final McpBackendExtensionService extensionService;
    private final McpTransportDescriptorService transportDescriptorService;

    /** initialize 返回值 —— 固定 serverInfo + 最小能力声明。 */
    public Map<String, Object> initialize() {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        Map<String, Object> toolsCap = new LinkedHashMap<>();
        toolsCap.put("listChanged", false);
        capabilities.put("tools", toolsCap);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("serverInfo", serverInfo);
        result.put("capabilities", capabilities);
        return result;
    }

    /** ping 返回空对象 —— MCP 协议 ping 标准响应。 */
    public Map<String, Object> ping() {
        return Map.of();
    }

    /**
     * 聚合 tools/list —— 遍历当前用户所有 enabled backend,把它们的 tool 加前缀后拼成一个 list。
     * 顺序按 backend_id ASC,同一 backend 内按 tool_name ASC,稳定可重现。
     *
     * @param tokenId Bearer Token id(从 Mcp-Session 拿);null = 当作非严格模式
     */
    public List<Map<String, Object>> listTools(Long userId, Long tokenId) {
        List<String> authorizedBackends = tokenId == null ? null : tokenService.getAuthorizedBackendIds(tokenId);
        List<McpBackendDO> backends = backendService.listEnabled();
        Map<String, McpBackendDO> backendByBackendId = new LinkedHashMap<>();
        for (McpBackendDO backend : backends) {
            backendByBackendId.put(backend.getBackendId(), backend);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int filteredBackends = 0;
        for (McpBackendDO b : backends) {
            if (isBuiltinBackend(b)) {
                continue;
            }
            // authorizedBackends == null = 全开放;非 null = 只返列表内 backend
            if (authorizedBackends != null && !authorizedBackends.contains(b.getBackendId())) {
                filteredBackends++;
                continue;
            }
            List<McpBackendToolDO> tools = backendToolMapper.selectByBackendId(b.getId());
            Map<String, McpToolOverrideDO> overrides = toolOverrideService.mapByToolName(b.getId());
            for (McpBackendToolDO t : tools) {
                out.add(toTool(b, t, overrides.get(t.getToolName())));
            }
        }
        for (McpProvider provider : providerRegistry.list()) {
            for (McpProviderServer server : provider.listEnabledServers()) {
                if (authorizedBackends != null && !authorizedBackends.contains(server.serverId())) {
                    continue;
                }
                McpBackendDO backend = backendByBackendId.get(server.serverId());
                Map<String, McpToolOverrideDO> overrides = backend == null
                        ? Map.of()
                        : toolOverrideService.mapByToolName(backend.getId());
                for (McpProviderTool tool : provider.listTools(server.serverId())) {
                    out.add(toProviderTool(server, backend, tool, overrides.get(tool.name())));
                }
            }
        }
        log.info("[aggregate] tools/list userId={} tokenId={} backendCount={} filteredBackends={} toolCount={}",
                userId, tokenId, backends.size(), filteredBackends, out.size());
        return out;
    }

    /**
     * tools/call —— 解析 {@code <backendId>__<toolName>} → 找到 backend → 转发 callTool。
     * 找到 backend 后跟单 tool 测试走同一路径(McpClientExecutor.withClient + callTool)。
     *
     * <p>写日志覆盖所有失败路径(token 未授权 / tool 不存在 / 远端错误 / 业务 isError),
     * 避免审计缺失;tool 不存在和 token 未授权报错分离,前者走 JSON-RPC -32601 NotFound。
     */
    public Map<String, Object> callTool(Long userId, Long tokenId, String prefixedName, Map<String, Object> arguments,
                                        String inboundSessionId,
                                        String clientIp, String userAgent) {
        long t0 = System.currentTimeMillis();
        String traceId = TraceIdUtil.currentOrNew();
        int sep = prefixedName.indexOf(TOOL_NAME_SEP);
        if (sep <= 0 || sep == prefixedName.length() - TOOL_NAME_SEP.length()) {
            // 无 backend 上下文,记 log 没意义;直接抛
            throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.INVALID_ARGUMENT,
                    "tool name 格式错误,期望 <backendId>" + TOOL_NAME_SEP + "<toolName>: " + prefixedName);
        }
        String backendId = prefixedName.substring(0, sep);
        String toolName = prefixedName.substring(sep + TOOL_NAME_SEP.length());
        McpBackendDO persistedBackend = backendService.selectByBackendId(backendId);
        if (persistedBackend != null) {
            toolName = toolOverrideService.resolveOriginalToolName(persistedBackend.getId(), toolName);
        }

        Optional<ProviderRoute> providerRoute = findProviderRoute(backendId);
        if (providerRoute.isPresent()) {
            ProviderRoute route = providerRoute.get();
            return callProviderTool(route.provider(), route.backend(), route.server(), userId, tokenId, toolName,
                    arguments, inboundSessionId, clientIp, userAgent);
        }

        McpBackendDO backend = persistedBackend;
        if (backend == null) {
            // 无 backend 上下文,记 log 没意义;直接抛
            throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.NOT_FOUND,
                    "backend 不存在: " + backendId);
        }

        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String argumentsJson = JsonUtil.toJson(args);
        log.info("[aggregate] tools/call trace={} userId={} tokenId={} backendId={} toolName={} argBytes={} inboundSid={}",
                traceId, userId, tokenId, backendId, toolName, argumentsJson.length(), inboundSessionId);

        String storedToolName = "tools/call:" + toolName;

        try {
            // 1. backend 启用校验
            if (backend.getEnabled() == null || backend.getEnabled() != 1) {
                throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.BACKEND_DISABLED,
                        "backend 已停用: " + backendId);
            }
            // 必须先判断 tool 是否存在，再做 token 授权，避免把配置问题误报成权限问题。
            long toolCount = backendToolMapper.countByBackendIdAndToolName(backend.getId(), toolName);
            if (toolCount == 0) {
                throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.NOT_FOUND,
                        "tool 不存在: " + prefixedName);
            }
            if (tokenId != null && !tokenService.hasToolAccess(tokenId, backendId, toolName)) {
                log.warn("[aggregate] tool access denied trace={} tokenId={} backend={} tool={}",
                        traceId, tokenId, backendId, toolName);
                throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.FORBIDDEN,
                        "token 未被授权调用该 tool: " + prefixedName);
            }

            String originalToolName = toolName;
            com.imawx.mcp.gateway.core.McpClientExecutor.Outcome<
                    io.modelcontextprotocol.spec.McpSchema.CallToolResult> outcome =
                    clientExecutor.withClient(backend, c -> c.callTool(
                            new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(originalToolName, args)));
            io.modelcontextprotocol.spec.McpSchema.CallToolResult result = outcome.result();
            int cost = (int) (System.currentTimeMillis() - t0);

            // MCP 协议层业务错误也要映射到调用日志失败，保证统计口径准确。
            boolean transportOk = true;
            String errorCode = null;
            String errorMessage = null;
            boolean bizError = Boolean.TRUE.equals(result.isError());
            if (bizError) {
                transportOk = false;
                errorCode = "MCP_TOOL_BUSINESS_ERROR";
                // 抽取第一个 text content 当 errorMessage(若有);给运维一眼可见的根因
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
            log.info("[aggregate] tools/call ok trace={} backendId={} toolName={} userId={} costMs={} inboundSid={} outboundSid={} isError={}",
                    traceId, backendId, toolName, userId, cost, inboundSessionId, outcome.outboundSessionId(), bizError);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("content", toContentList(result.content()));
            out.put("isError", bizError);
            return out;
        } catch (BizException e) {
            // 前置校验失败也写 FAILED log，保证审计不漏失败调用意图。
            int cost = (int) (System.currentTimeMillis() - t0);
            callLogService.writeLog(traceId, userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, null,
                    argumentsJson, null, null,
                    false, e.getErrorCode().name(), e.getMessage(), cost,
                    clientIp, userAgent);
            throw e;
        } catch (Exception e) {
            int cost = (int) (System.currentTimeMillis() - t0);
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            callLogService.writeLog(traceId, userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, null,
                    argumentsJson, null, null,
                    false, "MCP_REMOTE_ERROR", msg, cost,
                    clientIp, userAgent);
            log.error("[aggregate] tools/call fail trace={} backendId={} toolName={} err={}",
                    traceId, backendId, toolName, msg, e);
            throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.MCP_REMOTE_ERROR,
                    "工具调用失败: " + msg);
        }
    }

    private Map<String, Object> callProviderTool(McpProvider provider, McpBackendDO backend, McpProviderServer server,
                                                 Long userId, Long tokenId, String toolName,
                                                 Map<String, Object> arguments, String inboundSessionId,
                                                 String clientIp, String userAgent) {
        long t0 = System.currentTimeMillis();
        String traceId = TraceIdUtil.currentOrNew();
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String argumentsJson = JsonUtil.toJson(args);
        try {
            boolean toolExists = provider.listTools(server.serverId()).stream().anyMatch(t -> t.name().equals(toolName));
            if (!toolExists) {
                throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.NOT_FOUND,
                        "内置 provider tool 不存在: " + server.serverId() + TOOL_NAME_SEP + toolName);
            }
            if (tokenId != null && !tokenService.hasToolAccess(tokenId, server.serverId(), toolName)) {
                throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.FORBIDDEN,
                        "token 未被授权调用该内置 provider tool: " + server.serverId() + TOOL_NAME_SEP + toolName);
            }
            McpProviderCallResult result = provider.callTool(new McpProviderCallRequest(
                    userId, tokenId, server.serverType(), server.serverId(), toolName,
                    args, inboundSessionId, clientIp, userAgent));
            int cost = (int) (System.currentTimeMillis() - t0);
            String resultJson = truncate(JsonUtil.toJson(result), RESULT_JSON_MAX);
            callLogService.writeLog(traceId, userId, backend, "tools/call:" + toolName,
                    server.serverType(), inboundSessionId, null, argumentsJson, resultJson, null,
                    !result.isError(), result.isError() ? "PROVIDER_TOOL_ERROR" : null,
                    result.isError() ? "provider returned isError=true" : null,
                    cost, clientIp, userAgent);
            return Map.of("content", result.content(), "isError", result.isError());
        } catch (BizException e) {
            int cost = (int) (System.currentTimeMillis() - t0);
            callLogService.writeLog(traceId, userId, backend, "tools/call:" + toolName,
                    server.serverType(), inboundSessionId, null, argumentsJson, null, null,
                    false, e.getErrorCode().name(), e.getMessage(), cost, clientIp, userAgent);
            throw e;
        } catch (Exception e) {
            int cost = (int) (System.currentTimeMillis() - t0);
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            callLogService.writeLog(traceId, userId, backend, "tools/call:" + toolName,
                    server.serverType(), inboundSessionId, null, argumentsJson, null, null,
                    false, "PROVIDER_MCP_ERROR", msg, cost, clientIp, userAgent);
            throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.MCP_REMOTE_ERROR,
                    "内置 provider 工具调用失败: " + msg);
        }
    }

    private Optional<ProviderRoute> findProviderRoute(String serverId) {
        for (McpProvider provider : providerRegistry.list()) {
            for (McpProviderServer server : provider.listEnabledServers()) {
                if (server.serverId().equals(serverId)) {
                    McpBackendDO backend = backendService.selectByBackendId(server.serverId());
                    if (backend == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new ProviderRoute(provider, backend, server));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isBuiltinBackend(McpBackendDO backend) {
        if (backend == null || backend.getTransportType() == null) return false;
        String type = backend.getTransportType().trim().toUpperCase();
        return "MYSQL".equals(type) || "POSTGRESQL".equals(type)
                || "ORACLE".equals(type) || "SQLSERVER".equals(type)
                || "REDIS".equals(type) || "ALIYUN_DNS".equals(type)
                || "ALIYUN_OSS".equals(type) || "OPENAPI".equals(type);
    }

    private record ProviderRoute(McpProvider provider, McpBackendDO backend, McpProviderServer server) {
    }

    /**
     * 从 CallToolResult.content 抽取第一个 text content 当 errorMessage。
     * MCP tool 业务错误(如 SQL 执行失败)通常通过 TextContent 携带具体原因;
     * 调用方只看到 isError=true 没法排查,运维查日志时一眼能看到根因。
     */
    private static String extractFirstTextContent(
            List<io.modelcontextprotocol.spec.McpSchema.Content> content) {
        if (content == null) return null;
        for (io.modelcontextprotocol.spec.McpSchema.Content c : content) {
            if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc) {
                return tc.text();
            }
        }
        return null;
    }

    /**
     * 跟 {@code McpToolSyncService} 的私有 truncate 逻辑一致 —— 截断到 N 字符并加 marker,
     * 避免单个字段超 MEDIUMTEXT 上限(16MB 一般够,但流式 logging 累计可能爆)。
     */
    private static final int RESULT_JSON_MAX = 256 * 1024;
    private static final int ERROR_MAX = 1024;

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...[truncated]";
    }

    /**
     * 流式 tools/call，给标准 MCP controller 的 SSE 响应路径用。
     * 跟 {@link #callTool} 区别:用 {@code withStreamingClient} 注册 logging / progress
     * notification handler,server 推的中间事件通过 {@code loggingConsumer} 回调
     * 给调用方(用来 SSE 推给客户端)。最终 callTool result 走普通路径返。
     *
     * <p>消费者签名: {@code (level, data) -> void} —— level 是 logging level
     * (info/warning/error),data 是 server 推的 logging data(Map 或 String)。
     *
     * <p>跟 {@link #callTool} 一样覆盖所有失败路径写 FAILED log，业务 isError 也映射到 success=0。
     */
    public Map<String, Object> callToolStreaming(
            Long userId, Long tokenId, String prefixedName, Map<String, Object> arguments,
            String inboundSessionId,
            String clientIp, String userAgent,
            java.util.function.BiConsumer<String, Object> loggingConsumer) {

        int sep = prefixedName.indexOf(TOOL_NAME_SEP);
        if (sep <= 0 || sep == prefixedName.length() - TOOL_NAME_SEP.length()) {
            // 无 backend 上下文,记 log 没意义;直接抛
            throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.INVALID_ARGUMENT,
                    "tool name 格式错误,期望 <backendId>" + TOOL_NAME_SEP + "<toolName>: " + prefixedName);
        }
        String backendId = prefixedName.substring(0, sep);
        String toolName = prefixedName.substring(sep + TOOL_NAME_SEP.length());

        Optional<ProviderRoute> providerRoute = findProviderRoute(backendId);
        if (providerRoute.isPresent()) {
            ProviderRoute route = providerRoute.get();
            return callProviderTool(route.provider(), route.backend(), route.server(), userId, tokenId, toolName,
                    arguments, inboundSessionId, clientIp, userAgent);
        }

        McpBackendDO backend = backendService.selectByBackendId(backendId);
        if (backend == null) {
            // 无 backend 上下文,记 log 没意义;直接抛
            throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.NOT_FOUND,
                    "backend 不存在: " + backendId);
        }

        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String argumentsJson = JsonUtil.toJson(args);
        String storedToolName = "tools/call:" + toolName;
        // 计时起点放在所有前置校验之前 —— BizException catch 里也要用,放 try 外侧避免
        // "可能未初始化"编译错
        long t0 = System.currentTimeMillis();

        try {
            // 1. backend 启用校验
            if (backend.getEnabled() == null || backend.getEnabled() != 1) {
                throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.BACKEND_DISABLED,
                        "backend 已停用: " + backendId);
            }
            // 2. tool 存在性校验 —— 跟 callTool 一致
            long toolCount = backendToolMapper.countByBackendIdAndToolName(backend.getId(), toolName);
            if (toolCount == 0) {
                throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.NOT_FOUND,
                        "tool 不存在: " + prefixedName);
            }
            // 3. 严格模式下校验 token 是否有权调该 tool
            if (tokenId != null && !tokenService.hasToolAccess(tokenId, backendId, toolName)) {
                log.warn("[aggregate] sse tool access denied tokenId={} backend={} tool={}",
                        tokenId, backendId, toolName);
                throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.FORBIDDEN,
                        "token 未被授权调用该 tool: " + prefixedName);
            }

            java.util.function.Consumer<io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification> loggingAdapter =
                    notif -> loggingConsumer.accept(
                            notif.level() == null ? "info" : notif.level().name().toLowerCase(),
                            notif.data());
            java.util.function.Consumer<io.modelcontextprotocol.spec.McpSchema.ProgressNotification> progressAdapter =
                    notif -> {
                        // progress 也通过 logging 通道推(level=info + data 含 progress)
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("progress", notif.progress());
                        if (notif.total() != null) data.put("total", notif.total());
                        if (notif.message() != null) data.put("message", notif.message());
                        loggingConsumer.accept("info", data);
                    };

            // 4. 远程调用(streaming 模式)
            com.imawx.mcp.gateway.core.McpClientExecutor.Outcome<
                    io.modelcontextprotocol.spec.McpSchema.CallToolResult> outcome =
                    clientExecutor.withStreamingClient(backend,
                            loggingAdapter, progressAdapter,
                            c -> c.callTool(
                                    new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(toolName, args))
                                    .block());
            io.modelcontextprotocol.spec.McpSchema.CallToolResult result = outcome.result();
            int cost = (int) (System.currentTimeMillis() - t0);

            // 5. 业务 isError → success=0(跟 callTool 一致)
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

            // 写日志(同 callTool 路径 —— 调过的是同一条 client,流式信息已通过 SSE 推给客户端)
            McpInvokeResultVO vo = toResultVO(result);
            String resultJson = truncate(JsonUtil.toJson(vo), RESULT_JSON_MAX);
            // streaming 路径暂未接入完整 trace 串联。
            callLogService.writeLog("stream-mcp-std", userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, outcome.outboundSessionId(),
                    argumentsJson, resultJson, null,
                    transportOk, errorCode, errorMessage, cost,
                    clientIp, userAgent);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("content", toContentList(result.content()));
            out.put("isError", bizError);
            return out;
        } catch (BizException e) {
            int cost = (int) (System.currentTimeMillis() - t0);
            callLogService.writeLog("stream-mcp-std", userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, null,
                    argumentsJson, null, null,
                    false, e.getErrorCode().name(), e.getMessage(), cost,
                    clientIp, userAgent);
            throw e;
        } catch (Exception e) {
            int cost = (int) (System.currentTimeMillis() - t0);
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            log.error("[aggregate] sse tools/call fail backendId={} toolName={} err={}",
                    backendId, toolName, msg, e);
            callLogService.writeLog("stream-mcp-std", userId, backend, storedToolName,
                    backend.getTransportType(), inboundSessionId, null,
                    argumentsJson, null, null,
                    false, "MCP_REMOTE_ERROR", msg, cost,
                    clientIp, userAgent);
            throw new BizException(com.imawx.mcp.gateway.common.enums.BizErrorCode.MCP_REMOTE_ERROR,
                    "工具调用失败: " + msg);
        }
    }

    /** McpSchema.CallToolResult → 写日志用的 VO(含 content / isError,无 structuredContent)。 */
    private static McpInvokeResultVO toResultVO(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        return McpInvokeResultVO.builder()
                .content(toContentBlocks(result.content()))
                .isError(Boolean.TRUE.equals(result.isError()))
                .build();
    }

    /** McpSchema.Content list → VO 的 ContentBlock 列表(写日志的 resultJson 字段用)。 */
    private static List<com.imawx.mcp.gateway.entity.vo.McpInvokeResultVO.ContentBlock> toContentBlocks(
            List<io.modelcontextprotocol.spec.McpSchema.Content> content) {
        List<com.imawx.mcp.gateway.entity.vo.McpInvokeResultVO.ContentBlock> out = new ArrayList<>();
        if (content == null) {
            return out;
        }
        for (io.modelcontextprotocol.spec.McpSchema.Content c : content) {
            com.imawx.mcp.gateway.entity.vo.McpInvokeResultVO.ContentBlock block =
                    com.imawx.mcp.gateway.entity.vo.McpInvokeResultVO.ContentBlock.builder()
                            .type(c.type())
                            .build();
            Object data = null;
            String mimeType = null;
            if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc) {
                data = tc.text();
            } else if (c instanceof io.modelcontextprotocol.spec.McpSchema.ImageContent ic) {
                data = ic.data();
                mimeType = ic.mimeType();
            } else if (c instanceof io.modelcontextprotocol.spec.McpSchema.AudioContent ac) {
                data = ac.data();
                mimeType = ac.mimeType();
            } else if (c instanceof io.modelcontextprotocol.spec.McpSchema.EmbeddedResource er) {
                data = er.resource() == null ? null : Objects.toString(er.resource());
            }
            block.setData(data);
            if (mimeType != null) {
                block.setMimeType(mimeType);
            }
            out.add(block);
        }
        return out;
    }

    /** McpSchema.Content list → JSON-RPC content 列表(text/image/audio/resource)。 */
    private static List<Map<String, Object>> toContentList(List<io.modelcontextprotocol.spec.McpSchema.Content> content) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (content == null) {
            return out;
        }
        for (io.modelcontextprotocol.spec.McpSchema.Content c : content) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", c.type());
            Object data = null;
            String mimeType = null;
            if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc) {
                data = tc.text();
            } else if (c instanceof io.modelcontextprotocol.spec.McpSchema.ImageContent ic) {
                data = ic.data();
                mimeType = ic.mimeType();
            } else if (c instanceof io.modelcontextprotocol.spec.McpSchema.AudioContent ac) {
                data = ac.data();
                mimeType = ac.mimeType();
            } else if (c instanceof io.modelcontextprotocol.spec.McpSchema.EmbeddedResource er) {
                data = er.resource() == null ? null : Objects.toString(er.resource());
            }
            block.put("data", data);
            if (mimeType != null) {
                block.put("mimeType", mimeType);
            }
            out.add(block);
        }
        return out;
    }

    /** 把 backend tool 转成 MCP Tool 结构(加 backendId__ 前缀)。 */
    private Map<String, Object> applyOverrideToPrefixedTool(Map<String, Object> rawTool, McpBackendDO backend) {
        if (backend == null) {
            return rawTool;
        }
        String prefixedName = Objects.toString(rawTool.get("name"), "");
        String originalName = parseToolName(prefixedName);
        if (originalName == null) {
            return rawTool;
        }
        McpToolOverrideDO override = toolOverrideService.mapByToolName(backend.getId()).get(originalName);
        Map<String, Object> tool = new LinkedHashMap<>(rawTool);
        tool.put("name", backend.getBackendId() + TOOL_NAME_SEP
                + toolOverrideService.displayName(originalName, override));
        String desc = toolOverrideService.description(Objects.toString(rawTool.get("description"), ""), override);
        tool.put("description", toolDescription(backend, desc));
        if (override != null && override.getInputSchema() != null && !override.getInputSchema().isBlank()) {
            tool.put("inputSchema", JsonUtil.fromJson(override.getInputSchema(), new TypeReference<Map<String, Object>>() {}));
        }
        return tool;
    }

    private Map<String, Object> toTool(McpBackendDO b, McpBackendToolDO t, McpToolOverrideDO override) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", b.getBackendId() + TOOL_NAME_SEP
                + toolOverrideService.displayName(t.getToolName(), override));
        String origDesc = toolOverrideService.description(t.getDescription(), override);
        tool.put("description", toolDescription(b, origDesc));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        String schemaJson = toolOverrideService.inputSchema(t.getInputSchema(), override);
        if (schemaJson != null && !schemaJson.isBlank()) {
            try {
                inputSchema = JsonUtil.fromJson(schemaJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                // inputSchema 解析失败时退化成空 schema,不让单个脏数据阻塞整个列表
            }
        }
        if (inputSchema.isEmpty()) {
            inputSchema.put("type", "object");
        }
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    private Map<String, Object> toProviderTool(McpProviderServer server, McpBackendDO backend, McpProviderTool providerTool,
                                               McpToolOverrideDO override) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", server.serverId() + TOOL_NAME_SEP
                + toolOverrideService.displayName(providerTool.name(), override));
        String desc = toolOverrideService.description(providerTool.description(), override);
        tool.put("description", backend == null ? "[" + server.name() + "] " + nullToEmpty(desc) : toolDescription(backend, desc));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        String schemaJson = toolOverrideService.inputSchema(providerTool.inputSchema(), override);
        if (schemaJson != null && !schemaJson.isBlank()) {
            inputSchema = JsonUtil.fromJson(schemaJson, new TypeReference<Map<String, Object>>() {});
        }
        if (inputSchema == null || inputSchema.isEmpty()) {
            inputSchema = new LinkedHashMap<>();
            inputSchema.put("type", "object");
        }
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    private String toolDescription(McpBackendDO backend, String toolDescription) {
        String context = backendContext(backend);
        String desc = nullToEmpty(toolDescription).trim();
        return desc.isBlank() ? context : context + " " + desc;
    }

    private String backendContext(McpBackendDO backend) {
        Map<String, Object> config = extensionService.config(backend.getId());
        List<String> parts = new ArrayList<>();
        parts.add("MCP实例=" + text(backend.getServerName()));
        parts.add("实例ID=" + text(backend.getBackendId()));
        parts.add("类型=" + text(backend.getTransportType()));
        String env = environmentOf(backend, config);
        if (env != null) {
            parts.add("环境=" + env);
        }
        String scope = resourceScope(backend, config);
        if (scope != null) {
            parts.add("资源范围=" + scope);
        }
        List<String> tags = parseTags(backend.getTags());
        if (!tags.isEmpty()) {
            parts.add("标签=" + String.join(",", tags));
        }
        String aliases = joinList(config.get("aliases"));
        if (aliases != null && !aliases.isBlank()) {
            parts.add("别名=" + compact(aliases, 160));
        }
        if (backend.getRemark() != null && !backend.getRemark().isBlank()) {
            parts.add("备注=" + compact(backend.getRemark(), 160));
        }
        parts.add("选择规则=多个同类MCP时必须按实例名/实例ID/资源范围/标签匹配,不要跨实例使用");
        return "[" + String.join(" | ", parts) + "]";
    }

    private String resourceScope(McpBackendDO backend, Map<String, Object> config) {
        return transportDescriptorService.resourceScope(backend, config);
    }

    private String environmentOf(McpBackendDO backend, Map<String, Object> config) {
        String explicit = firstNonBlank(str(config.get("env")), str(config.get("environment")));
        if (explicit != null) {
            return explicit;
        }
        List<String> candidates = new ArrayList<>(parseTags(backend.getTags()));
        candidates.add(backend.getServerName());
        candidates.add(backend.getRemark());
        candidates.add(backend.getBackendId());
        for (String raw : candidates) {
            if (raw == null) continue;
            String v = raw.toLowerCase();
            if (v.contains("prod") || v.contains("生产")) return "prod";
            if (v.contains("staging") || v.contains("stage") || v.contains("预发")) return "staging";
            if (v.contains("test") || v.contains("测试")) return "test";
            if (v.contains("dev") || v.contains("开发")) return "dev";
            if (v.contains("uat")) return "uat";
        }
        return null;
    }

    private static List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = JsonUtil.fromJson(tagsJson, new TypeReference<List<String>>() {});
            if (tags == null) {
                return List.of();
            }
            return tags.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .limit(12)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String joinList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Objects::toString)
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .limit(20)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        if (value instanceof String s) {
            return s.trim();
        }
        return Objects.toString(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : Objects.toString(value).trim();
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : compact(value.trim(), 120);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String compact(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, Math.max(0, max - 3)) + "...";
    }

    /** 工具方法：判断 name 是否为带前缀格式。 */
    public static boolean isPrefixedToolName(String name) {
        if (name == null) return false;
        int sep = name.indexOf(TOOL_NAME_SEP);
        return sep > 0 && sep < name.length() - TOOL_NAME_SEP.length();
    }

    /** 工具方法：从 name 解析 backendId(tool name 那一段不返)。 */
    public static String parseBackendId(String prefixedName) {
        int sep = prefixedName.indexOf(TOOL_NAME_SEP);
        return sep > 0 ? prefixedName.substring(0, sep) : null;
    }

    /** 工具方法：从 name 解析 tool name。 */
    public static String parseToolName(String prefixedName) {
        int sep = prefixedName.indexOf(TOOL_NAME_SEP);
        return sep > 0 ? prefixedName.substring(sep + TOOL_NAME_SEP.length()) : null;
    }

}
