package com.imawx.mcp.gateway.service.mcpproxy;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.security.SensitiveDataMasker;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.entity.do_.McpBackendToolDO;
import com.imawx.mcp.gateway.entity.do_.McpCallLogDO;
import com.imawx.mcp.gateway.entity.do_.McpSessionDO;
import com.imawx.mcp.gateway.entity.do_.McpApiTokenDO;
import com.imawx.mcp.gateway.entity.do_.McpUserDO;
import com.imawx.mcp.gateway.entity.dto.McpCallLogQueryDTO;
import com.imawx.mcp.gateway.entity.enums.InvokeStatusEnum;
import com.imawx.mcp.gateway.entity.vo.McpCallLogVO;
import com.imawx.mcp.gateway.mapper.McpBackendToolMapper;
import com.imawx.mcp.gateway.mapper.McpCallLogMapper;
import com.imawx.mcp.gateway.mapper.McpCallLogMapper.McpCallLogQuery;
import com.imawx.mcp.gateway.mapper.McpSessionMapper;
import com.imawx.mcp.gateway.mapper.McpApiTokenMapper;
import com.imawx.mcp.gateway.mapper.McpUserMapper;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具调用日志查询服务(2026-07-01 加)。
 *
 * <p>当前唯一调用方是同步服务(写日志),本 Service 只读不写 —— 写入由
 * {@link McpToolSyncService#writeLog} 负责(单条 insert,不走 XML)。
 *
 * <p>账号隔离:所有查询强绑 {@code userId},不在 XML 暴露的接口允许跨用户查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpCallLogService {

    private static final int WRITE_QUEUE_CAPACITY = 10000;

    /**
     * 调用超时阈值(毫秒) —— 超过这个值 success=1 也归类为 TIMEOUT。
     *
     * <p>2026-07-03 改:60_000 → 1_800_000(30 分钟),跟
     * {@code McpGatewayProperties.Invoke.requestTimeoutSeconds=1800} 对齐。
     * 原因:网关已经把单次调用技术上限放到 30 分钟,统计阈值继续用 60s 会把所有 1 分钟以上的
     * 慢查询都标成 TIMEOUT —— 但慢查询(OLAP/大模型推理)本身业务上就是正常的 SUCCESS。
     * 让技术 timeout 和统计阈值保持一致,慢查询在 dashboard 里归 SUCCESS。
     *
     * <p>前端列表筛选 status=TIMEOUT 时,SQL 展开成
     * {@code success=1 AND cost_ms >= 1_800_000};SUCCESS = success=1 AND cost_ms < 1_800_000。
     *
     * <p>调成可配置(配置文件 / 数据库)留给阶段 3 + 实际使用反馈后再做。
     */
    public static final int TIMEOUT_THRESHOLD_MS = 1_800_000;

    private final McpCallLogMapper callLogMapper;
    private final McpBackendToolMapper backendToolMapper;
    private final BuiltinMcpDefinitionService definitionService;
    private final McpBackendExtensionService backendExtensionService;
    private final McpSessionMapper sessionMapper;
    private final McpApiTokenMapper tokenMapper;
    private final McpUserMapper userMapper;
    private final BlockingQueue<CallLogWriteRequest> writeQueue = new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread writerThread;

    @PostConstruct
    public void startWriter() {
        writerThread = Thread.ofVirtual()
                .name("call-log-writer")
                .start(this::drainLoop);
    }

    @PreDestroy
    public void stopWriter() {
        running.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
        }
    }

    private void drainLoop() {
        while (running.get() || !writeQueue.isEmpty()) {
            try {
                CallLogWriteRequest request = writeQueue.poll(1, TimeUnit.SECONDS);
                if (request != null) {
                    writeLogNow(request);
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    break;
                }
            } catch (Exception e) {
                log.warn("[call-log] writer loop failed: {}", e.toString());
            }
        }
    }

    /**
     * 分页查询(账号隔离,前端 ArtSearchBar 透传字段)。
     *
     * @param userId  当前登录用户 ID
     * @param query   过滤条件 + 分页
     * @return 记录列表 + 总数 + 分页元数据
     */
    public PageResult<McpCallLogVO> page(Long userId, McpCallLogQueryDTO query) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        McpCallLogQuery q = toQuery(userId, query);
        long total = callLogMapper.countByQuery(q);
        List<McpCallLogVO> records = total == 0 ? List.of() : callLogMapper.selectPageList(q);
        // status 字段前端契约字段(SUCCESS/FAILED/TIMEOUT),由 service 层补上
        records.forEach(this::fillStatus);
        return new PageResult<>(records, total, query.getPageNum(), query.getPageSize());
    }

    /**
     * 单条详情(账号隔离)。
     *
     * <p>查不到 / 不属于当前用户 → 抛 404,不区分"不存在"还是"无权限",
     * 避免泄露其他用户的日志 ID 存在性。
     */
    public McpCallLogVO detail(Long userId, Long id) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        if (id == null) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "id 不能为空");
        }
        McpCallLogVO vo = callLogMapper.selectDetail(id, userId);
        if (vo == null) {
            throw new BizException(BizErrorCode.NOT_FOUND, "调用日志不存在或无权访问");
        }
        fillStatus(vo);
        return vo;
    }

    /**
     * 聚合统计(今日调用数 / 成功率 / 平均耗时 / 超时数)。
     *
     * <p>{@code endTime} 不传默认当前时刻 —— 跟前端"今日"语义一致;
     * {@code startTime} 不传默认 {@code endTime - 24h}。
     *
     * <p>注意:dashboard 实时拉,SQL 走全表扫;日志量大后考虑加缓存层(5s 一次)。
     */
    public com.imawx.mcp.gateway.entity.vo.McpCallLogStatsVO stats(Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
        LocalDateTime start = startTime != null ? startTime : end.minusHours(24);
        return callLogMapper.selectStats(userId, start, end, TIMEOUT_THRESHOLD_MS);
    }

    // ===== Dashboard 大屏监控(2026-07-02 加)=====

    /** 状态分布(SUCCESS/FAILED/TIMEOUT 计数)给 dashboard 环形图用 */
    public java.util.List<java.util.Map<String, Object>> statusDistribution(
            Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        requireUser(userId);
        var range = normalizeRange(startTime, endTime);
        return callLogMapper.selectStatusDistribution(userId, range[0], range[1], TIMEOUT_THRESHOLD_MS);
    }

    /** 24h 分时调用 — dashboard 24h 热力图用 */
    public java.util.List<java.util.Map<String, Object>> hourlyTrend(
            Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        requireUser(userId);
        var range = normalizeRange(startTime, endTime);
        return callLogMapper.selectHourlyTrend(userId, range[0], range[1]);
    }

    /** Top 10 慢请求 — dashboard 慢请求表 */
    public java.util.List<java.util.Map<String, Object>> slowRequests(
            Long userId, LocalDateTime startTime, LocalDateTime endTime, int limit) {
        requireUser(userId);
        var range = normalizeRange(startTime, endTime);
        return callLogMapper.selectSlowRequests(userId, range[0], range[1], Math.max(1, limit));
    }

    /** 各 backend 聚合 — dashboard backend 状态网格用 */
    public java.util.List<java.util.Map<String, Object>> backendStats(
            Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        requireUser(userId);
        var range = normalizeRange(startTime, endTime);
        return callLogMapper.selectBackendStats(userId, range[0], range[1]);
    }

    /**
     * 按 transport 分组的 24h 平均耗时趋势(2026-07-02 加)—— dashboard transport
     * 折线图用。
     *
     * <p>返回稀疏数据:{transportType, hour, avgCostMs}。前端按 transportType GROUP BY
     * 后补 0 拼 24h 完整序列。
     *
     * <p>调 Mapper 之前走 {@link #normalizeRange} 校正时间窗,跟其他 dashboard
     * 方法行为一致(默认 24h)。
     */
    public java.util.List<java.util.Map<String, Object>> transportCostTrend(
            Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        requireUser(userId);
        var range = normalizeRange(startTime, endTime);
        return callLogMapper.selectTransportCostTrend(userId, range[0], range[1]);
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
    }

    private LocalDateTime[] normalizeRange(LocalDateTime start, LocalDateTime end) {
        LocalDateTime e = end != null ? end : LocalDateTime.now();
        LocalDateTime s = start != null ? start : e.minusHours(24);
        return new LocalDateTime[]{s, e};
    }

    /**
     * DTO → MyBatis query holder。
     *
     * <p>status 字段翻译规则:
     * <ul>
     *   <li>SUCCESS → success=1 + minCostMs=0(等价 cost < timeoutMs,见 mapper XML 注释)</li>
     *   <li>FAILED  → success=0</li>
     *   <li>TIMEOUT → success=1 + minCostMs=timeoutMs</li>
     *   <li>null    → 不加 status 条件</li>
     * </ul>
     *
     * <p>注意 mapper XML 里 SUCCESS 是 {@code success=1 AND cost_ms < timeoutMs},
     * 所以传 success=1 且 minCostMs=0 等价于 cost<timeoutMs(因为 timeoutMs 默认 1_800_000 / 30 分钟);
     * TIMEOUT 用 minCostMs=timeoutMs 实现 cost>=timeoutMs。
     */
    private McpCallLogQuery toQuery(Long userId, McpCallLogQueryDTO dto) {
        Integer success = null;
        Integer minCostMs = null;
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            InvokeStatusEnum status = InvokeStatusEnum.fromCode(dto.getStatus().toUpperCase());
            if (status == null) {
                throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                        "status 不合法: " + dto.getStatus() + "(允许 SUCCESS / FAILED / TIMEOUT)");
            }
            switch (status) {
                case SUCCESS -> {
                    success = 1;
                    minCostMs = 0; // cost<timeoutMs
                }
                case FAILED -> success = 0;
                case TIMEOUT -> {
                    success = 1;
                    minCostMs = TIMEOUT_THRESHOLD_MS; // cost>=timeoutMs
                }
            }
        }
        int offset = Math.max(0, (dto.getPageNum() - 1) * dto.getPageSize());
        int size = Math.max(1, dto.getPageSize());
        return new McpCallLogQuery(
                userId,
                dto.getStartTime(),
                dto.getEndTime(),
                dto.getToolName(),
                dto.getBackendId(),
                dto.getServerName(),
                dto.getUserEmail(),
                dto.getTokenPrefix(),
                success,
                minCostMs,
                offset,
                size
        );
    }

    /**
     * 给 VO 补 status 字段 —— success+costMs → SUCCESS/FAILED/TIMEOUT。
     *
     * <p>不存 DB,只算 VO 字段,前端 chip 渲染 / 详情过滤都看这个字段。
     */
    private void fillStatus(McpCallLogVO vo) {
        if (vo.getSuccess() != null && vo.getSuccess() == 0) {
            vo.setStatus(InvokeStatusEnum.FAILED.getCode());
        } else if (vo.getCostMs() != null && vo.getCostMs() >= TIMEOUT_THRESHOLD_MS) {
            vo.setStatus(InvokeStatusEnum.TIMEOUT.getCode());
        } else {
            vo.setStatus(InvokeStatusEnum.SUCCESS.getCode());
        }
    }

    public record PageResult<T>(List<T> records, long total, int pageNum, int pageSize) {
    }

    /**
     * 写一条调用日志(2026-07-02 抽出来给聚合路由复用)。
     *
     * <p>调用方:
     * <ul>
     *   <li>{@link McpToolSyncService#sync / #testTool / #testToolStream} —— 单 tool 测试 + sync 阶段</li>
     *   <li>{@link McpAggregateService#callTool} —— 阶段 3 聚合路由的 {@code tools/call} 转发</li>
     * </ul>
     *
     * <p>字段语义详见 {@link McpCallLogDO} 注释。聚合路由跟单 tool 测试共用同一行 schema,
     * 区别只在 inboundSessionId(聚合路由是 API token 用户的 session,单 tool 测试是
     * Web 后台用户的 session —— 都来自 {@code HttpSession.getId()})。
     *
     * <p>失败不抛 —— 写日志不应该影响业务请求(用户原话"写日志失败不能让请求失败")。
     * 异常只 warn,业务链路照常返。
     *
     * @param traceId          链路追踪 ID
     * @param userId           当前账号(账号隔离)
     * @param backend          被调的 MCP backend(sync 时也是 backend 维度,tool=null)
     * @param toolName         调用的 tool name;sync 阶段为 null(用空串占位,DB NOT NULL)
     * @param transportType    HTTP/SSE/STDIO —— {@code McpBackendDO.getTransportType()}
     * @param inboundSessionId 调用方 session id(从 {@code session.getId()} 来)
     * @param outboundSessionId 被调方 session id(从 MCP server 反射拿,可能为 null)
     * @param argumentsJson    callTool 入参 JSON;sync 阶段为 null
     * @param resultJson       callTool 出参 JSON;sync 阶段是 tools_snapshot
     * @param streamLogsJson   SSE 流式 logging/progress 事件合并 JSON;普通调用为 null
     * @param success          true=成功 false=失败
     * @param errorCode        失败时的错误码
     * @param errorMessage     失败时的错误信息
     * @param costMs           耗时毫秒
     * @param clientIp         调用方 IP(2026-07-02 加)—— 从 {@code HttpServletRequest} 拿,
     *                         service 层不直接拿 request,controller 层解析后传进来
     * @param userAgent        调用方 User-Agent(2026-07-02 加)—— 同上
     */
    public void writeLog(String traceId, Long userId, McpBackendDO backend, String toolName,
                         String transportType, String inboundSessionId, String outboundSessionId,
                         String argumentsJson, String resultJson, String streamLogsJson,
                         boolean success, String errorCode, String errorMessage,
                         int costMs,
                         String clientIp, String userAgent) {
        CallLogWriteRequest request = new CallLogWriteRequest(traceId, userId, backend, toolName,
                transportType, inboundSessionId, outboundSessionId, argumentsJson, resultJson, streamLogsJson,
                success, errorCode, errorMessage, costMs, clientIp, userAgent);
        try {
            writeQueue.put(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[call-log] enqueue interrupted traceId={} backendId={} toolName={}",
                    traceId, backend == null ? null : backend.getId(), toolName);
        }
    }

    private void writeLogNow(CallLogWriteRequest request) {
        McpBackendDO backend = request.backend();
        Long backendId = backend == null ? null : backend.getId();
        String toolName = request.toolName();
        try {
            McpCallLogDO row = new McpCallLogDO();
            row.setTraceId(request.traceId());
            row.setUserId(request.userId());
            row.setUserEmailSnapshot(resolveUserEmail(request.userId()));
            Long tokenId = resolveTokenId(request.inboundSessionId());
            row.setTokenId(tokenId);
            row.setTokenPrefixSnapshot(resolveTokenPrefix(tokenId));
            row.setBackendId(backendId);
            row.setBackendKey(backend == null ? null : backend.getBackendId());
            row.setServerNameSnapshot(backend == null ? null : truncate(backend.getServerName(), 128));
            // sync 失败时还没确定到具体 tool;字段 NOT NULL 给空串占位,避免 SQL 报错
            row.setToolName(toolName == null ? "" : toolName);
            row.setToolDescriptionSnapshot(truncate(resolveToolDescription(backend, toolName), 1024));
            row.setTransportType(request.transportType());
            row.setInboundSessionId(request.inboundSessionId());
            row.setOutboundSessionId(request.outboundSessionId());
            row.setArgumentsJson(SensitiveDataMasker.redactJson(sanitizeArgumentsForAudit(backend,
                    request.transportType(), toolName, request.argumentsJson())));
            row.setResultJson(SensitiveDataMasker.redactJson(request.resultJson()));
            row.setStreamLogsJson(SensitiveDataMasker.redactJson(request.streamLogsJson()));
            row.setSuccess(request.success() ? 1 : 0);
            row.setErrorCode(request.errorCode());
            row.setErrorMessage(SensitiveDataMasker.redactText(request.errorMessage()));
            row.setCostMs(request.costMs());
            row.setClientIp(request.clientIp());
            row.setUserAgent(truncate(request.userAgent(), 512));
            row.setCreateTime(LocalDateTime.now());
            callLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[call-log] write call_log failed traceId={} backendId={} toolName={} err={}",
                    request.traceId(), backendId, toolName, e.getMessage());
        }
    }

    private record CallLogWriteRequest(
            String traceId,
            Long userId,
            McpBackendDO backend,
            String toolName,
            String transportType,
            String inboundSessionId,
            String outboundSessionId,
            String argumentsJson,
            String resultJson,
            String streamLogsJson,
            boolean success,
            String errorCode,
            String errorMessage,
            int costMs,
            String clientIp,
            String userAgent
    ) {
    }

    private String resolveToolDescription(McpBackendDO backend, String storedToolName) {
        if (backend == null || storedToolName == null || storedToolName.isBlank()) {
            return null;
        }
        String rawToolName = rawToolName(storedToolName);
        if (rawToolName == null || rawToolName.isBlank()) {
            return null;
        }
        try {
            return definitionService.tools(backend.getTransportType()).stream()
                    .filter(t -> t.name().equals(rawToolName))
                    .map(t -> t.description())
                    .findFirst()
                    .orElseGet(() -> backendToolMapper.selectByBackendId(backend.getId()).stream()
                            .filter(t -> rawToolName.equals(t.getToolName()))
                            .map(McpBackendToolDO::getDescription)
                            .findFirst()
                            .orElse(null));
        } catch (Exception e) {
            return null;
        }
    }

    private Long resolveTokenId(String inboundSessionId) {
        if (inboundSessionId == null || inboundSessionId.isBlank()) {
            return null;
        }
        try {
            McpSessionDO session = sessionMapper.selectBySessionId(inboundSessionId);
            return session == null ? null : session.getTokenId();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveTokenPrefix(Long tokenId) {
        if (tokenId == null) {
            return null;
        }
        try {
            McpApiTokenDO token = tokenMapper.selectById(tokenId);
            return token == null ? null : truncate(token.getTokenPrefix(), 16);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveUserEmail(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            McpUserDO user = userMapper.selectById(userId);
            return user == null ? null : truncate(user.getEmail(), 128);
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeArgumentsForAudit(McpBackendDO backend, String transportType, String storedToolName, String argumentsJson) {
        String rawToolName = rawToolName(storedToolName);
        if (!"ALIYUN_OSS".equalsIgnoreCase(transportType)
                || !"oss_put_object_file".equals(rawToolName)
                || argumentsJson == null
                || argumentsJson.isBlank()) {
            return argumentsJson;
        }
        Map<String, Object> args = JsonUtil.fromJson(argumentsJson, new TypeReference<Map<String, Object>>() {});
        if (args == null || args.isEmpty()) {
            return argumentsJson;
        }
        Map<String, Object> out = new LinkedHashMap<>(args);
        String bucket = firstNonBlank(
                Objects.toString(out.get("bucket"), "").trim(),
                configValue(backend, "bucket"),
                configValue(backend, "bucketName"));
        String key = Objects.toString(out.get("key"), "").trim();
        String ossUri = bucket.isEmpty() || key.isEmpty() ? "oss://<bucket>/<key>" : "oss://" + bucket + "/" + key;
        out.put("ossUri", ossUri);
        replaceIfPresent(out, "contentBase64", ossUri);
        replaceIfPresent(out, "file", ossUri);
        replaceIfPresent(out, "content", ossUri);
        replaceIfPresent(out, "base64", ossUri);
        replaceIfPresent(out, "data", ossUri);
        replaceIfPresent(out, "bytes", ossUri);
        return JsonUtil.toJson(out);
    }

    private static void replaceIfPresent(Map<String, Object> args, String key, String replacement) {
        if (args.containsKey(key)) {
            args.put(key, replacement);
        }
    }

    private String configValue(McpBackendDO backend, String key) {
        if (backend == null || backend.getId() == null) {
            return null;
        }
        Object value = backendExtensionService.config(backend.getId()).get(key);
        return value == null ? null : Objects.toString(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String rawToolName(String storedToolName) {
        String value = storedToolName.trim();
        if (value.startsWith("tools/call:")) {
            return value.substring("tools/call:".length()).trim();
        }
        if (value.startsWith("tools/stream:")) {
            return value.substring("tools/stream:".length()).trim();
        }
        return value.contains(":") ? null : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
