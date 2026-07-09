package com.imawx.mcp.gateway.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * HTTP 请求访问日志 VO。
 */
@Data
public class McpAccessLogVO {

    private String id;
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
    private String userEmail;
    private Long tokenId;
    private String tokenPrefix;
    private Integer authHeader;
    private LocalDateTime createTime;
}
