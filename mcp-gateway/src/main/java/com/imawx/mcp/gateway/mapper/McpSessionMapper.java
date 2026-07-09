package com.imawx.mcp.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imawx.mcp.gateway.entity.do_.McpSessionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * MCP session 表 mapper(2026-07-02 加)。
 *
 * <p>BaseMapper 自带 insert / update / selectById / deleteById,这里只补
 * 业务特定的:
 * <ul>
 *   <li>{@link #selectBySessionId} —— Mcp-Session-Id 查 active session</li>
 *   <li>{@link #touch} —— 刷新 last_active_at + expire_at</li>
 *   <li>{@link #selectExpired} —— @Scheduled cleanup 用</li>
 *   <li>{@link #close} —— 软关(state=CLOSED + close_time)</li>
 * </ul>
 */
@Mapper
public interface McpSessionMapper extends BaseMapper<McpSessionDO> {

    /**
     * 按 sessionId 查 session(不限 userId,账号隔离在 service 层用 userId 校验)。
     */
    McpSessionDO selectBySessionId(@Param("sessionId") String sessionId);

    /**
     * 刷新 last_active_at + expire_at(每次 method 调用调一次)。
     */
    int touch(@Param("id") Long id,
              @Param("lastActiveAt") LocalDateTime lastActiveAt,
              @Param("expireAt") LocalDateTime expireAt,
              @Param("lastMethod") String lastMethod);

    /**
     * 查所有 expire_at < now 的 session(给 cleanup job 用,带 limit 避免一次查太多)。
     */
    java.util.List<McpSessionDO> selectExpired(@Param("now") LocalDateTime now,
                                               @Param("limit") int limit);

    /**
     * 软关(state=CLOSED + close_time = now)。
     */
    int close(@Param("id") Long id,
              @Param("closeTime") LocalDateTime closeTime);
}
