package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单 tool 调用结果 VO(对应前端 {@code ImawxMcpCallToolResult},2026-07-01 重构)。
 *
 * <p>对应 MCP 协议 {@code CallToolResult}:
 * <ul>
 *   <li>{@code content} —— 多段内容列表,每段 {@link ContentBlock} 由 type 决定渲染(text / image / audio / resource)</li>
 *   <li>{@code isError} —— true 表示工具返回了错误(协议层成功,但业务失败)</li>
 *   <li>{@code structuredContent} —— MCP 2.0 标准结构化输出(任意 JSON)</li>
 * </ul>
 */
@Data
@Builder
public class McpInvokeResultVO {

    /** 内容段列表。 */
    private List<ContentBlock> content;

    /** 是否错误(协议层 OK 但工具自己报失败时为 true)。 */
    private Boolean isError;

    /** 结构化输出(MCP 2.0 typed JSON)。 */
    private Object structuredContent;

    /**
     * 内容段 —— 前端按 type 分发渲染。
     *
     * <ul>
     *   <li>{@code text} → text 字段是字符串</li>
     *   <li>{@code image} / {@code audio} → data 字段是 base64 字符串 + mimeType</li>
     *   <li>{@code resource} / {@code embeddedResource} → data 是 URI / blob 描述</li>
     * </ul>
     */
    @Data
    @Builder
    public static class ContentBlock {
        /** 内容类型:text / image / audio / resource / embeddedResource。 */
        private String type;
        /** 原始数据(text 字符串 / base64 / URI,具体看 type)。 */
        private Object data;
        /** MIME 类型(image / audio 用,例如 image/png)。 */
        private String mimeType;
    }
}