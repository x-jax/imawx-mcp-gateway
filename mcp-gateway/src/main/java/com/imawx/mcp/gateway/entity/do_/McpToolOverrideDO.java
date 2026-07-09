package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP Tool 元数据重写配置。
 */
@Data
@TableName("mcp_tool_override")
public class McpToolOverrideDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long backendId;

    private String toolName;

    private String displayName;

    private String description;

    private String inputSchema;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
