package com.imawx.mcp.gateway.service.mcpproxy;

import com.imawx.mcp.gateway.common.security.RsaOaepCipher;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpBackendExtensionDO;
import com.imawx.mcp.gateway.mapper.McpBackendExtensionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class McpBackendExtensionService {

    private final McpBackendExtensionMapper mapper;
    private final RsaOaepCipher cipher;

    public McpBackendExtensionDO getByBackendId(Long backendId) {
        return backendId == null ? null : mapper.selectByBackendId(backendId);
    }

    public Map<String, Object> config(Long backendId) {
        McpBackendExtensionDO row = getByBackendId(backendId);
        if (row == null || row.getConfigJson() == null || row.getConfigJson().isBlank()) {
            return Map.of();
        }
        Map<String, Object> map = JsonUtil.fromJson(row.getConfigJson(), new TypeReference<Map<String, Object>>() {});
        return map == null ? Map.of() : map;
    }

    public Map<String, Object> secret(Long backendId) {
        McpBackendExtensionDO row = getByBackendId(backendId);
        if (row == null || row.getSecretEnc() == null || row.getSecretEnc().isBlank()) {
            return Map.of();
        }
        String json = cipher.decrypt(row.getSecretEnc());
        Map<String, Object> map = JsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
        return map == null ? Map.of() : map;
    }

    @Transactional
    public void upsert(Long backendId, String providerType, Map<String, Object> config, Map<String, Object> secret) {
        McpBackendExtensionDO row = mapper.selectByBackendId(backendId);
        boolean insert = row == null;
        if (insert) {
            row = new McpBackendExtensionDO();
            row.setBackendId(backendId);
            row.setCreateTime(LocalDateTime.now());
        }
        row.setProviderType(providerType);
        row.setConfigJson(config == null || config.isEmpty() ? null : JsonUtil.toJson(config));
        if (secret != null && !secret.isEmpty()) {
            row.setSecretEnc(cipher.encrypt(JsonUtil.toJson(new LinkedHashMap<>(secret))));
        }
        row.setUpdateTime(LocalDateTime.now());
        if (insert) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    @Transactional
    public void deleteByBackendId(Long backendId) {
        mapper.deleteByBackendId(backendId);
    }
}
