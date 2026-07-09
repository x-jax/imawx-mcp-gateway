package com.imawx.mcp.gateway.service.mcpproxy.provider;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP Provider 注册表。
 *
 * <p>Spring 会自动收集所有 {@link McpProvider} bean。新增数据库、云厂商或内部系统
 * MCP 模块时，只需要实现接口并注册为 bean。
 */
@Component
public class McpProviderRegistry {

    private final Map<String, McpProvider> providers;

    public McpProviderRegistry(List<McpProvider> providerList) {
        Map<String, McpProvider> index = new LinkedHashMap<>();
        for (McpProvider provider : providerList) {
            String key = normalize(provider.serverType());
            if (index.containsKey(key)) {
                throw new IllegalStateException("重复的 MCP provider serverType: " + key);
            }
            index.put(key, provider);
        }
        this.providers = Collections.unmodifiableMap(index);
    }

    public List<McpProvider> list() {
        return List.copyOf(providers.values());
    }

    public Optional<McpProvider> find(String serverType) {
        return Optional.ofNullable(providers.get(normalize(serverType)));
    }

    private static String normalize(String serverType) {
        if (serverType == null || serverType.isBlank()) {
            throw new IllegalArgumentException("serverType 不能为空");
        }
        return serverType.trim().toUpperCase();
    }
}
