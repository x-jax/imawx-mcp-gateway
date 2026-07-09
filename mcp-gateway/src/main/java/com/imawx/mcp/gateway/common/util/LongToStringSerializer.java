package com.imawx.mcp.gateway.common.util;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdScalarSerializer;

/**
 * Long → String 序列化器，避免 JS Number 精度丢失（MySQL BIGINT 超过 2^53-1 时 JS 会截断）。
 *
 * <p>2026-07-03 改:Jackson 3 没有 {@code NumberSerializer}，改继承 {@link StdScalarSerializer}。
 * Jackson 3 的 {@code serialize} 抛 {@code JacksonException} 不是 {@code IOException}。
 *
 * <p>使用方式:VO 字段上 {@code @JsonSerialize(using = LongToStringSerializer.class)} 限定,
 * 不污染全局（不能 enable mapper feature 全局转换 Long）。
 */
public class LongToStringSerializer extends StdScalarSerializer<Long> {

    public LongToStringSerializer() {
        super(Long.class);
    }

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializationContext provider) {
        gen.writeString(value == null ? null : value.toString());
    }
}