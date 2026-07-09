package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局聚合工具 DO（{@code mcp_agg_tool}）。
 *
 * <p>{@code aggName} 命名规则：{@code {backendId}__{toolName}}，避免跨 backend 工具名冲突。
 * 阶段 3 启 MCP Server 时 {@code tools/list} 直接读这张表返回。
 */
@Data
@TableName("mcp_agg_tool")
public class McpAggToolDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 聚合名 {@code backendId__toolName}。 */
    private String aggName;

    private Long backendId;

    private String toolName;

    private String description;

    private String inputSchema;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}