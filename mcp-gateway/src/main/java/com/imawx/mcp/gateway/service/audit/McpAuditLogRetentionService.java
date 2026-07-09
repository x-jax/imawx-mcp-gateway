package com.imawx.mcp.gateway.service.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imawx.mcp.gateway.entity.do_.McpAccessLogDO;
import com.imawx.mcp.gateway.entity.do_.McpCallLogDO;
import com.imawx.mcp.gateway.mapper.McpAccessLogMapper;
import com.imawx.mcp.gateway.mapper.McpCallLogMapper;
import com.imawx.mcp.gateway.service.system.McpSystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpAuditLogRetentionService {

    public static final String CONFIG_ACCESS_RETENTION_DAYS = "mcp.audit.access-log.retention-days";
    public static final String CONFIG_CALL_RETENTION_DAYS = "mcp.audit.call-log.retention-days";
    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int MAX_RETENTION_DAYS = 90;

    private final McpSystemConfigService configService;
    private final McpAccessLogMapper accessLogMapper;
    private final McpCallLogMapper callLogMapper;

    @Scheduled(initialDelay = 120_000, fixedDelay = 86_400_000)
    public void cleanupScheduled() {
        cleanup();
    }

    public void cleanup() {
        int accessDays = retentionDays(CONFIG_ACCESS_RETENTION_DAYS);
        int callDays = retentionDays(CONFIG_CALL_RETENTION_DAYS);
        LocalDateTime accessCutoff = LocalDateTime.now().minusDays(accessDays);
        LocalDateTime callCutoff = LocalDateTime.now().minusDays(callDays);
        int accessDeleted = accessLogMapper.delete(
                new LambdaQueryWrapper<McpAccessLogDO>().lt(McpAccessLogDO::getCreateTime, accessCutoff));
        int callDeleted = callLogMapper.delete(
                new LambdaQueryWrapper<McpCallLogDO>().lt(McpCallLogDO::getCreateTime, callCutoff));
        if (accessDeleted > 0 || callDeleted > 0) {
            log.info("[audit-retention] accessDeleted={} accessDays={} callDeleted={} callDays={}",
                    accessDeleted, accessDays, callDeleted, callDays);
        }
    }

    private int retentionDays(String key) {
        int configured = configService.getInt(key, DEFAULT_RETENTION_DAYS);
        if (configured < 1) {
            return DEFAULT_RETENTION_DAYS;
        }
        return Math.min(configured, MAX_RETENTION_DAYS);
    }
}
