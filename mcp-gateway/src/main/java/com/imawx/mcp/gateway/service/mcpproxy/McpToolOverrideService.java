package com.imawx.mcp.gateway.service.mcpproxy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpToolOverrideDO;
import com.imawx.mcp.gateway.entity.dto.McpToolOverrideDTO;
import com.imawx.mcp.gateway.mapper.McpToolOverrideMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool 展示名、描述和入参 Schema 重写。
 */
@Service
@RequiredArgsConstructor
public class McpToolOverrideService {

    private final McpToolOverrideMapper mapper;

    public List<McpToolOverrideDO> list(Long backendId) {
        return mapper.selectList(new LambdaQueryWrapper<McpToolOverrideDO>()
                .eq(McpToolOverrideDO::getBackendId, backendId)
                .orderByAsc(McpToolOverrideDO::getToolName));
    }

    public Map<String, McpToolOverrideDO> mapByToolName(Long backendId) {
        Map<String, McpToolOverrideDO> out = new HashMap<>();
        for (McpToolOverrideDO row : list(backendId)) {
            out.put(row.getToolName(), row);
        }
        return out;
    }

    public String resolveOriginalToolName(Long backendId, String requestedName) {
        if (!StringUtils.hasText(requestedName)) {
            return requestedName;
        }
        McpToolOverrideDO row = mapper.selectOne(new LambdaQueryWrapper<McpToolOverrideDO>()
                .eq(McpToolOverrideDO::getBackendId, backendId)
                .eq(McpToolOverrideDO::getDisplayName, requestedName)
                .last("LIMIT 1"));
        return row == null ? requestedName : row.getToolName();
    }

    public String displayName(String originalName, McpToolOverrideDO override) {
        if (override != null && StringUtils.hasText(override.getDisplayName())) {
            return override.getDisplayName().trim();
        }
        return originalName;
    }

    public String description(String originalDescription, McpToolOverrideDO override) {
        if (override != null && StringUtils.hasText(override.getDescription())) {
            return override.getDescription();
        }
        return originalDescription;
    }

    public String inputSchema(String originalInputSchema, McpToolOverrideDO override) {
        if (override != null && StringUtils.hasText(override.getInputSchema())) {
            return override.getInputSchema();
        }
        return originalInputSchema;
    }

    public void save(Long backendId, McpToolOverrideDTO dto) {
        String displayName = trimToNull(dto.getDisplayName());
        String description = trimToNull(dto.getDescription());
        String inputSchema = trimToNull(dto.getInputSchema());
        validateInputSchema(inputSchema);
        if (displayName == null && description == null && inputSchema == null) {
            mapper.delete(new LambdaQueryWrapper<McpToolOverrideDO>()
                    .eq(McpToolOverrideDO::getBackendId, backendId)
                    .eq(McpToolOverrideDO::getToolName, dto.getToolName()));
            return;
        }
        if (displayName != null) {
            McpToolOverrideDO duplicate = mapper.selectOne(new LambdaQueryWrapper<McpToolOverrideDO>()
                    .eq(McpToolOverrideDO::getBackendId, backendId)
                    .eq(McpToolOverrideDO::getDisplayName, displayName)
                    .ne(McpToolOverrideDO::getToolName, dto.getToolName())
                    .last("LIMIT 1"));
            if (duplicate != null) {
                throw new BizException(BizErrorCode.INVALID_ARGUMENT, "展示名称已被其他 tool 使用: " + displayName);
            }
        }
        McpToolOverrideDO old = mapper.selectOne(new LambdaQueryWrapper<McpToolOverrideDO>()
                .eq(McpToolOverrideDO::getBackendId, backendId)
                .eq(McpToolOverrideDO::getToolName, dto.getToolName())
                .last("LIMIT 1"));
        McpToolOverrideDO row = old == null ? new McpToolOverrideDO() : old;
        row.setBackendId(backendId);
        row.setToolName(dto.getToolName());
        row.setDisplayName(displayName);
        row.setDescription(description);
        row.setInputSchema(inputSchema);
        row.setUpdateTime(LocalDateTime.now());
        if (old == null) {
            row.setCreateTime(LocalDateTime.now());
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    private static void validateInputSchema(String inputSchema) {
        if (!StringUtils.hasText(inputSchema)) {
            return;
        }
        Object obj = JsonUtil.fromJson(inputSchema, Object.class);
        if (!(obj instanceof Map<?, ?>)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "入参 Schema 必须是合法 JSON 对象");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
