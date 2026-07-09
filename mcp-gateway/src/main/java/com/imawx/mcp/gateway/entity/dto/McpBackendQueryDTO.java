package com.imawx.mcp.gateway.entity.dto;

import lombok.Data;

/** 分页查询 DTO。 */
@Data
public class McpBackendQueryDTO {

    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String keyword;
    private String transportType;
    private Integer enabled;
}