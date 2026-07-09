package com.imawx.mcp.gateway.entity.do_;

import lombok.Data;

/** 关系型数据库 MCP 运行时连接配置，不再映射独立数据库表。 */
@Data
public class McpDbConnectionDO {
    private Long id;
    private String connName;
    private String dbType;
    private String jdbcUrl;
    private String username;
    private String passwordEnc;
    private String schemaName;
    private Integer enabled;
}
