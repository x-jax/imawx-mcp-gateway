package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** API Token 表映射。 */
@Data
@TableName("mcp_api_token")
public class McpApiTokenDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String name;
    private String tokenPrefix;
    private String tokenHash;
    private String scopes;
    private String ipWhitelist;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private String lastUsedIp;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
    /** 0=全开放，1=严格授权。 */
    private Integer restrictMode;
}
