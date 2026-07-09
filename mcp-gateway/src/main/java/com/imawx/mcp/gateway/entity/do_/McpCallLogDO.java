package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调用日志 DO（{@code mcp_call_log}）。
 *
 * <p>写入方：{@code McpToolSyncService.writeLog} —— 单条 insert，不走 XML。
 *
 * <p>字段语义(2026-07-02 增列):
 * <ul>
 *   <li>{@code transportType} —— DB 落库的真实 transport(HTTP/SSE/STDIO)。
 *     跟 {@code mcp_backend.transport_type} 同名同义;前端表格里跟 chip 渲染对齐</li>
 *   <li>{@code inboundSessionId} —— 别人调我们时带的 HTTP session id
 *     ({@code session.getId()}),用于排查"同一个用户多次调"的链路</li>
 *   <li>{@code outboundSessionId} —— 我们调 MCP server 时 server 给的 session id,
 *     反射从 {@code McpClientTransport.activeSession.sessionId()} 拿,用于反查
 *     MCP server 自己的访问日志</li>
 *   <li>{@code streamLogsJson} —— SSE 流式 tool call 的 logging/progress 事件合并,
 *     一个 call 只写一条 log 记录;非流式为 null。格式:
 *     {@code [{"type":"logging","level":"info","data":...,"ts":...}, ...]}</li>
 * </ul>
 */
@Data
@TableName("mcp_call_log")
public class McpCallLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private Long userId;
    private String userEmailSnapshot;
    private Long tokenId;
    private String tokenPrefixSnapshot;
    private Long backendId;
    private String backendKey;
    private String serverNameSnapshot;
    private String toolName;
    private String toolDescriptionSnapshot;
    private String transportType;
    private String inboundSessionId;
    private String outboundSessionId;
    private String argumentsJson;
    private String resultJson;
    /** SSE 流式 logging/progress 事件合并存储(同步写一条 log)。 */
    private String streamLogsJson;
    private Integer success;
    private String errorCode;
    private String errorMessage;
    private Integer costMs;
    private LocalDateTime createTime;
    /**
     * 调用方 IP(2026-07-02 加)—— 从 {@code HttpServletRequest.getRemoteAddr()} 拿,
     * X-Forwarded-For 优先。VARCHAR(45) 兼容 IPv6(`::ffff:255.255.255.255` 形式 + 端口)。
     */
    private String clientIp;
    /**
     * 调用方 User-Agent(2026-07-02 加)—— MCP 客户端标识,溯源/审计用。
     * VARCHAR(512) 截断,Chrome 完整 UA 通常 < 200 字符够用。
     */
    private String userAgent;
}
