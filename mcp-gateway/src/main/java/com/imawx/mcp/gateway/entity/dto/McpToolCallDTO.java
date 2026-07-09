package com.imawx.mcp.gateway.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 内部测试 Client 调用入参 DTO。
 *
 * <p>阶段 1 调试用：指定 backendId + toolName + argumentsJson，调一次远端工具。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Data
public class McpToolCallDTO {

    @NotBlank(message = "backendId 不能为空")
    private String backendId;

    @NotBlank(message = "toolName 不能为空")
    private String toolName;

    /** 入参 JSON 字符串，例 {@code {"a":1,"b":"x"}}。 */
    @NotBlank(message = "argumentsJson 不能为空")
    private String argumentsJson;
}
