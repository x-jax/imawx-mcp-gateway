package com.imawx.mcp.gateway.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 系统配置项写入 DTO(2026-07-02 加)—— 覆盖式:存在则更新,不存在则插入。
 */
@Data
public class McpSystemConfigDTO {

    @NotBlank(message = "configKey 不能为空")
    @Size(max = 64, message = "configKey 长度不能超过 64")
    private String configKey;

    @NotNull(message = "configValue 不能为空")
    @Size(max = 4000, message = "configValue 长度不能超过 4000")
    private String configValue;

    @Size(max = 256)
    private String description;
}
