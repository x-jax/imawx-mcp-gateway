package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * HTTP 请求访问日志 DO（{@code mcp_access_log}）。
 *
 * <p>只保存审计元数据，不保存 query/body/cookie/Authorization 原文。
 */
@Data
@TableName("mcp_access_log")
public class McpAccessLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private String ip;
    private String method;
    private String uri;
    private String result;
    private Integer status;
    private Integer costMs;
    private Integer hasQuery;
    private String userAgent;
    private Long userId;
    private String userEmailSnapshot;
    private Long tokenId;
    private String tokenPrefixSnapshot;
    private String sessionId;
    private Integer authHeader;
    private LocalDateTime createTime;
}
