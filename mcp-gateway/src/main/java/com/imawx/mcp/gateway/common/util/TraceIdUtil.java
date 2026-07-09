package com.imawx.mcp.gateway.common.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路追踪 ID 工具。
 *
 * <p>阶段 1 仅做调用日志的 trace_id 关联，阶段 2/3 接入 OpenTelemetry 时整体替换。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
public final class TraceIdUtil {

    /** MDC key，与 logback-spring.xml pattern 中的 {@code %X{traceId}} 对应。 */
    public static final String MDC_KEY = "traceId";

    private TraceIdUtil() {
    }

    /**
     * 生成新的 traceId（UUID 去掉横线，32 字符）。
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 取当前 MDC 中的 traceId，没有则生成一个并放入 MDC。
     */
    public static String currentOrNew() {
        String tid = MDC.get(MDC_KEY);
        if (tid == null || tid.isEmpty()) {
            tid = generate();
            MDC.put(MDC_KEY, tid);
        }
        return tid;
    }

    /**
     * 显式放入 MDC（用于 HTTP 入口处）。
     */
    public static void set(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(MDC_KEY, traceId);
        }
    }

    /**
     * 清理 MDC（用于请求结束）。
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
