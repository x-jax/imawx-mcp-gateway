package com.imawx.mcp.gateway.entity.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * HTTP 请求访问日志分页查询 DTO。
 */
@Data
public class McpAccessLogQueryDTO {

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String keyword;
    private String userEmail;
    private String ip;
    private String method;
    private String result;
    private Integer status;
    private int pageNum = 1;
    private int pageSize = 20;
}
