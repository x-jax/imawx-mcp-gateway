package com.imawx.mcp.gateway.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/public")
public class PublicOpenApiSmokeController {

    @Value("${mcp-gateway.public-base-url:https://mcp.gateway.imawx.com}")
    private String publicBaseUrl;

    @GetMapping("/openapi.json")
    public Map<String, Object> openapi() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.0.3");
        root.put("info", Map.of(
                "title", "imawx MCP Gateway Smoke API",
                "description", "用于验证 Swagger / OpenAPI MCP 转换和转发链路的公开只读接口。",
                "version", "1.0.0"
        ));
        root.put("servers", List.of(Map.of("url", trimTrailingSlash(publicBaseUrl))));
        root.put("paths", Map.of(
                "/api/public/openapi-smoke/ping", Map.of(
                        "get", Map.of(
                                "operationId", "openapi_smoke_ping",
                                "summary", "OpenAPI MCP 只读连通性测试",
                                "description", "返回输入 message、当前时间和服务名，不访问任何敏感资源。",
                                "parameters", List.of(
                                        Map.of(
                                                "name", "message",
                                                "in", "query",
                                                "required", false,
                                                "description", "回显消息，用于验证 query 参数是否被正确透传。",
                                                "schema", Map.of("type", "string", "default", "pong")
                                        )
                                ),
                                "responses", Map.of(
                                        "200", Map.of(
                                                "description", "测试成功",
                                                "content", Map.of(
                                                        "application/json", Map.of(
                                                                "schema", Map.of(
                                                                        "type", "object",
                                                                        "properties", Map.of(
                                                                                "service", Map.of("type", "string", "description", "服务名"),
                                                                                "message", Map.of("type", "string", "description", "回显消息"),
                                                                                "time", Map.of("type", "string", "format", "date-time", "description", "服务端当前时间")
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        ));
        return root;
    }

    @GetMapping("/openapi-smoke/ping")
    public Map<String, Object> ping(@RequestParam(defaultValue = "pong") String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "imawx-mcp-gateway");
        out.put("message", message);
        out.put("time", OffsetDateTime.now().toString());
        return out;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://mcp.gateway.imawx.com";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
