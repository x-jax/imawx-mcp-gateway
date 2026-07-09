package com.imawx.mcp.gateway.service.mcpproxy;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.entity.do_.McpSessionDO;
import com.imawx.mcp.gateway.mapper.McpSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MCP 协议 session 管理(2026-07-02 阶段 3 标准协议改造加)。
 *
 * <p>对应 MCP Streamable HTTP 标准的 {@code Mcp-Session-Id} 头:
 * <ol>
 *   <li>客户端 POST initialize —— service 创 session + 返 sessionId</li>
 *   <li>客户端发 notifications/initialized —— state 转 ACTIVE</li>
 *   <li>后续 tools/list / tools/call 等都带 Mcp-Session-Id header
 *       —— service getActive 校验 + touch 续期</li>
 *   <li>shutdown / close 显式关 —— state=CLOSED</li>
 *   <li>TTL 1h 过 expire_at @Scheduled 自动清</li>
 * </ol>
 *
 * <p>账号隔离:校验 userId(防止 A 创建的 session 被 B 用)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpSessionService {

    /**
     * Session TTL —— 1 小时。client 每次 method 调用 refresh。
     * 长任务(比如 stream_logs 推 30 分钟)期间 expire_at 一直被刷,
     * 不会被 cleanup job 误删。
     */
    public static final int SESSION_TTL_MINUTES = 60;

    private static final int CLEANUP_BATCH = 200;

    private final McpSessionMapper sessionMapper;

    /**
     * 创建新 session(initialize 阶段)。
     *
     * @return 32 字符 sessionId(UUID 无 hyphen,跟 MCP 标准兼容)
     */
    @Transactional
    public String create(Long userId, Long tokenId, String protocolVersion, String clientInfo) {
        McpSessionDO row = new McpSessionDO();
        String sid = UUID.randomUUID().toString().replace("-", "");
        row.setSessionId(sid);
        row.setUserId(userId);
        row.setTokenId(tokenId);
        row.setProtocolVersion(protocolVersion);
        row.setClientInfo(clientInfo);
        row.setState("INITIALIZED");
        LocalDateTime now = LocalDateTime.now();
        row.setCreateTime(now);
        row.setLastActiveAt(now);
        row.setExpireAt(now.plusMinutes(SESSION_TTL_MINUTES));
        sessionMapper.insert(row);
        log.info("[mcp-session] created sid={} userId={} tokenId={} protocol={} clientInfo={}",
                sid, userId, tokenId, protocolVersion, clientInfo);
        return sid;
    }

    /**
     * 拿 active session + 校验 userId + touch 续期。
     *
     * <p>状态必须是 INITIALIZED / ACTIVE,CLOSED / 不存在 / 过期都返 401。
     *
     * @return 找到的 session
     * @throws BizException UNAUTHORIZED / NOT_FOUND
     */
    public McpSessionDO getActive(String sessionId, Long userId, String lastMethod) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "缺少 Mcp-Session-Id header");
        }
        McpSessionDO s = sessionMapper.selectBySessionId(sessionId);
        if (s == null) {
            throw new BizException(BizErrorCode.NOT_FOUND, "MCP session 不存在或已过期");
        }
        if (!userId.equals(s.getUserId())) {
            // 防止 A 创建的 session 被 B 用 —— 直接 401
            log.warn("[mcp-session] userId mismatch sid={} expected={} actual={}",
                    sessionId, s.getUserId(), userId);
            throw new BizException(BizErrorCode.UNAUTHORIZED, "MCP session 鉴权失败");
        }
        if ("CLOSED".equals(s.getState())) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "MCP session 已关闭");
        }
        if (s.getExpireAt() != null && s.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "MCP session 已过期");
        }
        // 续期
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.touch(s.getId(), now, now.plusMinutes(SESSION_TTL_MINUTES), lastMethod);
        s.setLastActiveAt(now);
        s.setExpireAt(now.plusMinutes(SESSION_TTL_MINUTES));
        s.setLastMethod(lastMethod);
        return s;
    }

    /**
     * 客户端发 notifications/initialized 后把状态从 INITIALIZED 转 ACTIVE。
     */
    @Transactional
    public void markActive(String sessionId, Long userId) {
        McpSessionDO s = sessionMapper.selectBySessionId(sessionId);
        if (s == null) return;
        if (!userId.equals(s.getUserId())) return;
        s.setState("ACTIVE");
        sessionMapper.updateById(s);
    }

    /**
     * 显式 close(客户端 shutdown / 服务端 close)。
     */
    @Transactional
    public void close(String sessionId, Long userId) {
        McpSessionDO s = sessionMapper.selectBySessionId(sessionId);
        if (s == null) return;
        if (!userId.equals(s.getUserId())) return;
        sessionMapper.close(s.getId(), LocalDateTime.now());
    }

    /**
     * 清理过期 session —— @Scheduled 每小时跑一次,带 limit 避免一次删太多。
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void cleanupExpired() {
        try {
            int total = 0;
            while (true) {
                List<McpSessionDO> expired = sessionMapper.selectExpired(LocalDateTime.now(), CLEANUP_BATCH);
                if (expired.isEmpty()) break;
                for (McpSessionDO s : expired) {
                    sessionMapper.deleteById(s.getId());
                }
                total += expired.size();
                if (expired.size() < CLEANUP_BATCH) break;
            }
            if (total > 0) {
                log.info("[mcp-session] cleaned {} expired sessions", total);
            }
        } catch (Exception e) {
            log.warn("[mcp-session] cleanup failed: {}", e.getMessage());
        }
    }
}
