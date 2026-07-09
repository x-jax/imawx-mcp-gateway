package com.imawx.mcp.gateway.common.config;

import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置占位 —— Jackson 3 (Spring Boot 4 默认 {@code tools.jackson.*},2026-07-03 改)。
 *
 * <p>关键决策:
 * <ul>
 *   <li>Jackson 3 删除了 {@code SerializationFeature.WRITE_ENUMS_USING_TO_STRING} 和
 *       {@code @JsonValue} 注解。{@code HealthStatus} / {@code TransportType} 的 code 跟 enum name
 *       完全一致,默认 enum.name() 序列化即可,前端无感知。</li>
 *   <li>Spring Boot 4.x 定制器接口是 {@code JsonMapperBuilderCustomizer}(Jackson 2 时代是
 *       {@code Jackson2ObjectMapperBuilderCustomizer})。</li>
 *   <li>Long 字段的 JS 精度丢失问题用 {@link com.imawx.mcp.gateway.common.util.LongToStringSerializer}
 *       通过 {@code @JsonSerialize(using=...)} 在 VO 字段上限定,不污染全局。</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {
}