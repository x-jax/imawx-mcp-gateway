package com.imawx.mcp.gateway.common.enums;

/**
 * 下游 MCP 服务的健康状态。
 *
 * <p>健康探测任务（{@code BackendHealthTask}）按该状态机更新数据库。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
public enum HealthStatus {

    /** 初始状态，尚未探测过。 */
    UNKNOWN("UNKNOWN"),

    /** 最近一次探测成功。 */
    HEALTHY("HEALTHY"),

    /** 连续失败次数超过阈值，已熔断。 */
    BROKEN("BROKEN");

    /**
     * 枚举 code —— 跟 enum name 一致,Jackson 3 默认 enum.name() 序列化(2026-07-03 改:Jackson 3
     * 完全移除了 @JsonValue 和 WRITE_ENUMS_USING_TO_STRING,但因 code==name,前端无感知)。
     */
    private final String code;

    HealthStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 按 code 解析枚举（大小写不敏感）。
     *
     * @param code 枚举 code
     * @return 命中的枚举
     * @throws IllegalArgumentException code 非法时抛出
     */
    public static HealthStatus fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        for (HealthStatus s : values()) {
            if (s.code.equalsIgnoreCase(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown health status: " + code);
    }
}
