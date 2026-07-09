package com.imawx.mcp.gateway.service.mcpproxy.provider.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpToolDefinition {
    String name();

    String description();

    String inputSchema() default "{\"type\":\"object\",\"properties\":{}}";

    String[] transports() default {};
}
