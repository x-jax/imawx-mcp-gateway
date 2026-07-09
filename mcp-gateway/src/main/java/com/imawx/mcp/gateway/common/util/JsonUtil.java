package com.imawx.mcp.gateway.common.util;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 序列化工具（基于 Jackson 3.x —— Spring Boot 4.1 默认 {@code tools.jackson}）。
 *
 * <p>单例 {@link ObjectMapper}，内置 JSR-310 时间模块、对未知字段宽容。
 *
 * <p>2026-07-03 改:Spring Boot 4 用 {@code tools.jackson.*}（Jackson 3）,
 * 跟经典 {@code com.fasterxml.jackson.*}（Jackson 2）API 大体兼容但包名全换。
 * Jackson 3 移除了 {@code ObjectMapper.configure(...)} 改用 builder 模式;
 * 移除了 {@code SerializationFeature.WRITE_DATES_AS_TIMESTAMPS}(默认输出 ISO 8601);
 * 移除了 {@code @JsonValue} 注解。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Slf4j
public final class JsonUtil {

    /**
     * Jackson 3 默认已经支持 JSR-310({@code LocalDateTime}/{@code LocalDate}),
     * 不需要像 Jackson 2 那样显式 registerModule(new JavaTimeModule());
     * 默认输出 ISO 8601 字符串(2026-07-03T...)而不是 timestamp 数字。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonUtil() {
    }

    /**
     * 对象 → JSON 字符串。失败时返回 {@code "{}"} 而不是抛异常，避免链路日志反序列化副作用。
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            log.warn("toJson failed, class={}", obj.getClass().getName(), e);
            return "{}";
        }
    }

    /**
     * JSON 字符串 → 对象。失败时返回 {@code null}。
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JacksonException e) {
            log.warn("fromJson failed, class={}", clazz.getName(), e);
            return null;
        }
    }

    /**
     * JSON 字符串 → 复杂类型（如 {@code Map<String,Object>}、{@code List<Foo>}）。
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JacksonException e) {
            log.warn("fromJson failed, typeRef={}", typeRef.getType(), e);
            return null;
        }
    }

    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}