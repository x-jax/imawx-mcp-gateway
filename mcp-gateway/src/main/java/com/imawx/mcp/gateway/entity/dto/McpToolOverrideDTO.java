package com.imawx.mcp.gateway.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Tool 元数据重写请求。
 */
@Data
public class McpToolOverrideDTO {

    @NotBlank(message = "toolName 不能为空")
    @Size(max = 128, message = "toolName 不能超过 128 个字符")
    private String toolName;

    @Size(max = 128, message = "展示名称不能超过 128 个字符")
    private String displayName;

    @Size(max = 4096, message = "描述不能超过 4096 个字符")
    private String description;

    private String inputSchema;
}
