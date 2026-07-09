package com.imawx.mcp.gateway.entity.enums;

import com.imawx.mcp.gateway.common.dict.DictOption;
import com.imawx.mcp.gateway.common.dict.DictKeys;

import java.util.Arrays;
import java.util.List;

/**
 * 用户状态枚举(2026-07-01 加,支撑字典枚举化重构)。
 *
 * <p>对齐 mcp_user.status 字段(TINYINT):1启用 / 0禁用。
 *
 * <p>前端 {@code /api/sys/constants} 的 {@code userStatus} key 返 DictOption list,
 * value 是 Integer (1/0),label 是中文。
 */
public enum UserStatusEnum {
    DISABLED(0, "禁用"),
    ENABLED(1, "启用");

    private final int code;
    private final String label;

    UserStatusEnum(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
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

    public static UserStatusEnum fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (UserStatusEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    public static String dictKey() {
        return DictKeys.USER_STATUS;
    }
}