package com.imawx.mcp.gateway.common.enums;

import com.imawx.mcp.gateway.common.dict.DictKeys;
import com.imawx.mcp.gateway.common.dict.DictOption;

import java.util.List;

/**
 * 远端 MCP 服务的传输类型。
 *
 * <p>对应 {@code mcp_backend.transport_type} 字段，取值需与数据库 VARCHAR(16) 列兼容。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
public enum TransportType {

    /** 进程内 STDIO 通信，通过命令 + 参数启动子进程。 */
    STDIO("STDIO"),

    /** 旧版 SSE 传输（HTTP，单向服务端推送）。 */
    SSE("SSE"),

    /** HTTP 传输（基于 MCP JSON-RPC over HTTP POST）—— 前端只 3 种 radio：HTTP / SSE / STDIO。
     *  底层走 Streamable HTTP transport 兼容老 HTTP JSON-RPC endpoint。 */
    HTTP("HTTP"),

    /** MCP 2.0 推荐的 Streamable HTTP 传输（HTTP，双向消息）。
     *  保留枚举值供旧数据 / API 调用方使用，但前端 radio 不再暴露；前端 HTTP radio 落库时
     *  仍按 HTTP 写入（语义最清晰），build() 内 HTTP 与 STREAMABLE_HTTP 走同一 transport。 */
    STREAMABLE_HTTP("STREAMABLE_HTTP");

    /**
     * 枚举 code —— 跟 enum name 一致,Jackson 3 默认 enum.name() 序列化(2026-07-03 改:Jackson 3
     * 完全移除了 @JsonValue 和 WRITE_ENUMS_USING_TO_STRING,但因 code==name,前端无感知)。
     */
    private final String code;

    TransportType(String code) {
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
    public static TransportType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("transport type code is null");
        }
        for (TransportType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown transport type: " + code);
    }

    /**
     * 全部传输类型 → DictOption 列表(给 SysConstantsController 拼字典用)。
     *
     * <p>前端只暴露 HTTP / SSE / STDIO 三种 —— STREAMABLE_HTTP 是历史保留值,不出现在字典里。
     * 如果未来前端要支持枚举全量,改成 {@code Arrays.stream(values())} 即可。
     */
    public static List<DictOption> asDictOptions() {
        return List.of(
                DictOption.of(STDIO.code, "STDIO", STDIO.name()),
                DictOption.of(SSE.code, "SSE", SSE.name()),
                DictOption.of(HTTP.code, "HTTP", HTTP.name())
        );
    }

    /** 字典 key —— 跟 DictKeys.PROTOCOL 一致。 */
    public static String dictKey() {
        return DictKeys.PROTOCOL;
    }
}
