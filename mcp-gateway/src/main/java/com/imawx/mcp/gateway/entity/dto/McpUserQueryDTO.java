package com.imawx.mcp.gateway.entity.dto;

import lombok.Data;

/**
 * 用户列表查询 DTO(2026-07-03 加)。
 */
@Data
public class McpUserQueryDTO {

    /** 关键字 —— 模糊匹配 username / email / displayName 之一。 */
    private String keyword;

    /** 1=启用 / 0=禁用;不传 = 全部。 */
    private Integer status;

    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
