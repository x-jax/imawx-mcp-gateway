package com.imawx.mcp.gateway.entity.dto;

import lombok.Data;

/**
 * 聚合工具分页查询 DTO。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Data
public class McpAggToolQueryDTO {

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    private String backendId;

    private String keyword;
}
