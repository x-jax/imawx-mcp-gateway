package com.imawx.mcp.gateway.entity.dto;

import lombok.Data;

import java.util.List;

/** 编辑外部 MCP server 入参。 */
@Data
public class McpBackendUpdateDTO {

    private Long id;

    private String serverName;
    private String transportType;
    private String endpoint;
    private String authToken;
    private String extraConfig;
    /** 用户标签(2026-07-01 加)。null = 不更新;传空数组 = 清空;非空 = 覆盖。 */
    private List<String> tags;
    private String remark;
    private Integer enabled;
    private Integer healthInterval;
    private Integer failThreshold;
}
