package com.imawx.mcp.gateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * WebMVC 全局配置。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final McpGatewayProperties properties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        McpGatewayProperties.Web.Cors cors = properties.getWeb().getCors();
        String[] allowedOrigins = Arrays.stream(cors.getAllowedOriginPatterns())
                .filter(origin -> origin != null && !origin.isBlank())
                .toArray(String[]::new);
        if (allowedOrigins.length == 0) {
            return;
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
