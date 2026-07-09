package com.imawx.mcp.gateway.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置项 VO（前端契约）。对应 {@code mcp_system_config} 表。
 */
@Data
public class McpSystemConfigVO {
    private String configKey;
    private String configValue;
    private String description;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
