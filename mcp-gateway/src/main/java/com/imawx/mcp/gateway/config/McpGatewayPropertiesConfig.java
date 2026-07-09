package com.imawx.mcp.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 {@link McpGatewayProperties} 配置绑定。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Configuration
@EnableConfigurationProperties(McpGatewayProperties.class)
public class McpGatewayPropertiesConfig {
}
