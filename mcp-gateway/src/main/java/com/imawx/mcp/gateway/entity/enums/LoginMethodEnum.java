package com.imawx.mcp.gateway.entity.enums;

import com.imawx.mcp.gateway.common.dict.DictOption;
import com.imawx.mcp.gateway.common.dict.DictKeys;

import java.util.Arrays;
import java.util.List;

/**
 * 登录方式枚举(2026-07-01 加,支撑字典枚举化重构)。
 *
 * <p>阶段 1 只实现 {@link #PASSWORD} 账号密码登录,其他方式(SMS / OAuth / SSO)后续按需开启。
 * 前端 {@code /api/sys/constants} 的 {@code loginMethods} + {@code loginMethodLabels}
 * 两个 key 合并成单个 {@code loginMethods} list,每条 {@link DictOption} 的 {@code value}
 * 是 enum name,前端读 {@code label} 直接渲染。
 */
public enum LoginMethodEnum {
    PASSWORD("password", "账号密码");

    private final String code;
    private final String label;

    LoginMethodEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 全部登录方式 → DictOption 列表,给 SysConstantsController 拼字典用。 */
    public static List<DictOption> asDictOptions() {
        return Arrays.stream(values())
                .map(e -> DictOption.of(e.code, e.label, e.name()))
                .toList();
    }

    /** 给前端 {@code loginMethods} 这个 key 用 —— 业务值是 {@code code},不是 enum name。 */
    public static List<DictOption> asDict() {
        return Arrays.stream(values())
                .map(e -> DictOption.of(e.code, e.label))
                .toList();
    }

    /** 反查:从业务 code 找枚举(给 Service 层验证用)。 */
    public static LoginMethodEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (LoginMethodEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }

    /** 字典 key —— 跟 DictKeys.LOGIN_METHODS 一致。 */
    public static String dictKey() {
        return DictKeys.LOGIN_METHODS;
    }
}