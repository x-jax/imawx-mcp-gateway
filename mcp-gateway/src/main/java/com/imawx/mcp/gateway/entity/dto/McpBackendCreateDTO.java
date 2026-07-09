package com.imawx.mcp.gateway.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 新增外部 MCP server 入参。 */
@Data
public class McpBackendCreateDTO {

    /** 业务主键（可选；不传则后端生成）。 */
    @Size(max = 64)
    private String backendId;

    @NotBlank(message = "serverName 不能为空")
    @Size(max = 128)
    private String serverName;

    @NotBlank(message = "transportType 不能为空")
    @Size(max = 32)
    private String transportType;

    @NotBlank(message = "endpoint 不能为空")
    @Size(max = 512)
    private String endpoint;

    @Size(max = 512)
    private String authToken;

    /** STDIO 的 args/env（JSON 字符串，可空）。 */
    private String extraConfig;

    /** 用户标签（字符串数组,可空）。
     *  2026-07-01 加:每个 tag 单标签字符串,最多 32 字符,最多 20 个。
     *  Service 层负责 null/空数组 → 存 NULL,非空 → 存 JSON 数组。 */
    @Size(max = 20)
    private List<@Size(max = 32) String> tags;

    @Size(max = 512)
    private String remark;

    private Integer enabled;
    private Integer healthInterval;
    private Integer failThreshold;
}