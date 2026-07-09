package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** MCP 后端扩展配置 DO。 */
@Data
@TableName("mcp_backend_extension")
public class McpBackendExtensionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long backendId;
    private String providerType;
    private String configJson;
    private String secretEnc;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
