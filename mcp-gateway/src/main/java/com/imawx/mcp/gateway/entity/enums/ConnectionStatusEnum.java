package com.imawx.mcp.gateway.entity.enums;

import com.imawx.mcp.gateway.common.dict.DictKeys;
import com.imawx.mcp.gateway.common.dict.DictOption;

import java.util.Arrays;
import java.util.List;

/** 外部 MCP server 连接状态（对齐前端 {@code ImawxConnectionStatus}）。 */
public enum ConnectionStatusEnum {

    DISCONNECTED(0, "DISCONNECTED", "未连接"),
    CONNECTED(1, "CONNECTED", "已连接"),
    FAILED(2, "FAILED", "连接失败");

    private final int code;
    private final String label;
    /** 2026-07-01 加:中文展示名(之前是英文,前端 chip 直接显示英文太硬)。
     *  之前前端拿 "DISCONNECTED" 直接渲染,现在改成"未连接"更友好。 */
    private final String displayLabel;

    ConnectionStatusEnum(int code, String label, String displayLabel) {
        this.code = code;
        this.label = label;
        this.displayLabel = displayLabel;
    }

    public int getCode() {
        return code;
    }

    /** JSON 序列化用的 key(DISCONNECTED/CONNECTED/FAILED,跟前端契约对齐)。 */
    public String getLabel() {
        return label;
    }

    /** 中文展示名(给前端 chip 直接用)。 */
    public String getDisplayLabel() {
        return displayLabel;
    }

    public static ConnectionStatusEnum fromCode(Integer code) {
        if (code == null) {
            return DISCONNECTED;
        }
        for (ConnectionStatusEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return DISCONNECTED;
    }

    /** 全部状态 → DictOption 列表(给 SysConstantsController 拼字典 + McpBackendVO 用)。 */
    public static List<DictOption> asDictOptions() {
        return Arrays.stream(values())
                .map(e -> DictOption.of(e.label, e.displayLabel, e.name()))
                .toList();
    }

    public static String dictKey() {
        return DictKeys.CONNECTION_STATUS;
    }
}