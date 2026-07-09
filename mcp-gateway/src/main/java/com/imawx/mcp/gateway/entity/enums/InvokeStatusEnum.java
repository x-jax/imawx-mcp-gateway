package com.imawx.mcp.gateway.entity.enums;

import com.imawx.mcp.gateway.common.dict.DictOption;
import com.imawx.mcp.gateway.common.dict.DictKeys;

import java.util.Arrays;
import java.util.List;

/**
 * 工具调用日志状态枚举(2026-07-01 加)。
 *
 * <p>对应 mcp_call_log 表:
 * <ul>
 *   <li>{@code success=1} 且 {@code cost_ms < timeout_threshold} → {@link #SUCCESS}</li>
 *   <li>{@code success=1} 但 {@code cost_ms >= timeout_threshold} → {@link #TIMEOUT} (业务超时)</li>
 *   <li>{@code success=0} → {@link #FAILED}</li>
 * </ul>
 *
 * <p>DB 只存 success TINYINT + cost_ms,不直接存 status 字符串 —— status 由后端 Service
 * 按规则推导,避免 enum 值跟 DB 字段脱节。
 *
 * <p>前端 {@code /api/sys/constants} 的 {@code invokeStatus} key 返 DictOption list,
 * value 是字符串 (SUCCESS/TIMEOUT/FAILED)。
 */
public enum InvokeStatusEnum {
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    TIMEOUT("TIMEOUT", "超时");

    private final String code;
    private final String label;

    InvokeStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static List<DictOption> asDictOptions() {
        return Arrays.stream(values())
                .map(e -> DictOption.of(e.code, e.label, e.name()))
                .toList();
    }

    public static InvokeStatusEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (InvokeStatusEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }

    public static String dictKey() {
        return DictKeys.INVOKE_STATUS;
    }
}