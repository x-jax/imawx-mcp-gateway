package com.imawx.mcp.gateway.core;

import com.imawx.mcp.gateway.common.enums.HealthStatus;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 健康探测：仅调 {@code initialize()}，连通即视为健康。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpHealthChecker {

    private final McpClientExecutor executor;

    public Result check(McpBackendDO backend) {
        try {
            executor.withClient(backend, c -> null);
            return Result.healthy(backend.getFailCount() == null ? 0 : backend.getFailCount());
        } catch (Exception e) {
            int failCount = (backend.getFailCount() == null ? 0 : backend.getFailCount()) + 1;
            int threshold = backend.getFailThreshold() == null ? 3 : backend.getFailThreshold();
            boolean broken = failCount >= threshold;
            log.warn("[health-check] fail backendId={} count={}/{} broken={} err={}",
                    backend.getBackendId(), failCount, threshold, broken, e.getMessage());
            return Result.unhealthy(failCount, broken, e.getMessage());
        }
    }

    public HealthStatus resolveStatus(Result r) {
        if (r.healthy()) {
            return HealthStatus.HEALTHY;
        }
        return r.broken() ? HealthStatus.BROKEN : HealthStatus.UNKNOWN;
    }

    public record Result(boolean healthy, int newFailCount, boolean broken, String error) {
        public static Result healthy(int oldCount) {
            return new Result(true, 0, false, null);
        }
        public static Result unhealthy(int count, boolean broken, String error) {
            return new Result(false, count, broken, error);
        }
    }
}
