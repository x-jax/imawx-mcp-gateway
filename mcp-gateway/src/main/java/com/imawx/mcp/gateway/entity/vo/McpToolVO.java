package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 远端原始工具 VO，对应前端 {@code ImawxMcpTool}。
 */
@Data
@Builder
public class McpToolVO {

    private String id;
    private String name;
    private String originalName;
    private String description;
    private String inputSchema;
    private LocalDateTime updateTime;
}
