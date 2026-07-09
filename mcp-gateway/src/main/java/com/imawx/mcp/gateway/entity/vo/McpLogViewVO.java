package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 日志查看响应 VO(对应前端 {@code ImawxLogView},2026-07-01 加)。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code file}: 源文件名</li>
 *   <li>{@code lines}: 日志条目,已按 level 过滤 + 按文件倒序取末尾 N 行</li>
 *   <li>{@code fileSize}: 文件原始字节大小(给前端展示"已读 X / 总 Y")</li>
 * </ul>
 */
@Data
@Builder
public class McpLogViewVO {

    private String file;
    private List<String> lines;
    private long fileSize;
}