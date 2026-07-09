package com.imawx.mcp.gateway.service.audit;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.entity.do_.McpAccessLogDO;
import com.imawx.mcp.gateway.entity.do_.McpApiTokenDO;
import com.imawx.mcp.gateway.entity.do_.McpUserDO;
import com.imawx.mcp.gateway.entity.dto.McpAccessLogQueryDTO;
import com.imawx.mcp.gateway.entity.vo.McpAccessLogVO;
import com.imawx.mcp.gateway.mapper.McpAccessLogMapper;
import com.imawx.mcp.gateway.mapper.McpAccessLogMapper.McpAccessLogQuery;
import com.imawx.mcp.gateway.mapper.McpApiTokenMapper;
import com.imawx.mcp.gateway.mapper.McpUserMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP 请求访问日志服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpAccessLogService {

    private static final int QUEUE_CAPACITY = 10000;

    private final McpAccessLogMapper accessLogMapper;
    private final McpUserMapper userMapper;
    private final McpApiTokenMapper tokenMapper;
    private final BlockingQueue<AccessLogWriteRequest> writeQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread writerThread;

    @PostConstruct
    public void startWriter() {
        writerThread = Thread.ofVirtual()
                .name("access-log-writer")
                .start(this::drainLoop);
    }

    @PreDestroy
    public void stopWriter() {
        running.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
        }
    }

    public void enqueueLog(AccessLogWriteRequest request) {
        if (request == null) {
            return;
        }
        try {
            writeQueue.put(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[access-log-db] enqueue interrupted uri={} method={} status={}",
                    request.uri(), request.method(), request.status());
        }
    }

    private void drainLoop() {
        while (running.get() || !writeQueue.isEmpty()) {
            try {
                AccessLogWriteRequest request = writeQueue.poll(1, TimeUnit.SECONDS);
                if (request != null) {
                    writeLog(request);
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    break;
                }
            } catch (Exception e) {
                log.warn("[access-log-db] writer loop failed: {}", e.toString());
            }
        }
    }

    public void writeLog(AccessLogWriteRequest request) {
        try {
            McpAccessLogDO row = new McpAccessLogDO();
            row.setTraceId(request.traceId());
            row.setIp(required(request.ip(), "ip"));
            row.setMethod(required(request.method(), "method"));
            row.setUri(required(request.uri(), "uri"));
            row.setResult(required(request.result(), "result"));
            row.setStatus(request.status());
            row.setCostMs(request.costMs());
            row.setHasQuery(request.hasQuery() ? 1 : 0);
            row.setUserAgent(request.userAgent());
            row.setUserId(request.userId());
            row.setUserEmailSnapshot(resolveUserEmail(request.userId(), request.userEmail()));
            row.setTokenId(request.tokenId());
            row.setTokenPrefixSnapshot(resolveTokenPrefix(request.tokenId(), request.tokenPrefix()));
            row.setSessionId(request.sessionId());
            row.setAuthHeader(request.authHeader() ? 1 : 0);
            row.setCreateTime(LocalDateTime.now());
            accessLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[access-log-db] write failed: {}", e.toString());
        }
    }

    public PageResult<McpAccessLogVO> page(Long userId, McpAccessLogQueryDTO dto) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        McpAccessLogQueryDTO query = dto == null ? new McpAccessLogQueryDTO() : dto;
        String result = normalizeResult(query.getResult());
        String method = query.getMethod();
        Integer status = query.getStatus();
        if (status != null && (status < 100 || status > 599)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "status 不合法");
        }
        int pageNum = Math.max(1, query.getPageNum());
        int pageSize = Math.min(200, Math.max(1, query.getPageSize()));
        McpAccessLogQuery q = new McpAccessLogQuery(
                query.getStartTime(),
                query.getEndTime(),
                query.getKeyword(),
                query.getUserEmail(),
                query.getIp(),
                method == null ? null : method.trim().toUpperCase(),
                result,
                status,
                (pageNum - 1) * pageSize,
                pageSize
        );
        long total = accessLogMapper.countByQuery(q);
        List<McpAccessLogVO> records = total == 0 ? List.of() : accessLogMapper.selectPageList(q);
        return new PageResult<>(records, total, pageNum, pageSize);
    }

    private static String normalizeResult(String value) {
        String v = value == null ? null : value.trim();
        if (v == null) {
            return null;
        }
        String upper = v.toUpperCase();
        if (!"SUCCESS".equals(upper) && !"FAILED".equals(upper)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "result 不合法: " + value);
        }
        return upper;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            return field;
        }
        return value;
    }

    private String resolveUserEmail(Long userId, String requestEmail) {
        String email = normalizeEmail(requestEmail);
        if (email != null) {
            return email;
        }
        if (userId == null) {
            return null;
        }
        try {
            McpUserDO user = userMapper.selectById(userId);
            return user == null ? null : normalizeEmail(user.getEmail());
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveTokenPrefix(Long tokenId, String requestTokenPrefix) {
        String prefix = normalizeTokenPrefix(requestTokenPrefix);
        if (prefix != null) {
            return prefix;
        }
        if (tokenId == null) {
            return null;
        }
        try {
            McpApiTokenDO token = tokenMapper.selectById(tokenId);
            return token == null ? null : normalizeTokenPrefix(token.getTokenPrefix());
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeEmail(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || v.length() > 128 || !v.contains("@")) {
            return null;
        }
        return v;
    }

    private static String normalizeTokenPrefix(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || v.length() > 16) {
            return null;
        }
        return v;
    }

    public record AccessLogWriteRequest(
            String traceId,
            String ip,
            String method,
            String uri,
            String result,
            Integer status,
            Integer costMs,
            boolean hasQuery,
            String userAgent,
            Long userId,
            String userEmail,
            Long tokenId,
            String tokenPrefix,
            String sessionId,
            boolean authHeader
    ) {
    }

    public record PageResult<T>(List<T> records, long total, int pageNum, int pageSize) {
    }
}
