package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 远端原始工具 DO（{@code mcp_backend_tool}）。
 *
 * <p>同步时全量替换该 backend 下的全部记录。{@code inputSchema} 是 JSON Schema 序列化字符串。
 */
@Data
@TableName("mcp_backend_tool")
public class McpBackendToolDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属外部 MCP server。 */
    private Long backendId;

    /** 远端工具原始名（不带前缀）。 */
    private String toolName;

    private String description;

    private String inputSchema;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}