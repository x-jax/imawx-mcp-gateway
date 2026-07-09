package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调用日志 VO（对齐前端 {@code ImawxInvokeLog} interface）。
 *
 * <p>id 用 String 输出（避免 JS Number 精度丢失；DB BIGINT 自增）。
 *
 * <p>{@code status} 由 Service 层根据 {@code success} + {@code costMs} 推导:
 * <ul>
 *   <li>success=1 且 costMs &lt; timeout → SUCCESS</li>
 *   <li>success=1 且 costMs &gt;= timeout → TIMEOUT</li>
 *   <li>success=0 → FAILED</li>
 * </ul>
 *
 * <p>{@code argumentsJson / resultJson / streamLogsJson} 直接透传 DB 里的 JSON 字符串,
 * 前端按需 JSON.parse;不再做服务端格式化(避免字段太长时序化开销)。
 *
 * <p>2026-07-02 增字段 — {@code transportType / inboundSessionId / outboundSessionId}
 * 用于定位"调用方 session"和"被调方 session"两端链路,排查跨服务问题时一目了然。
 */
@Data
@Builder
public class McpCallLogVO {

    private String id;
    /** 链路追踪 ID,跨服务排障用。 */
    private String traceId;
    private Long userId;
    private String userEmail;
    private String tokenPrefix;
    private Long backendId;
    /** MCP 后端业务标识(mcp_backend.backend_id),内置 MCP 日志用它回填服务名。 */
    private String backendKey;
    /** 服务名 —— 优先使用调用时快照,兼容 backend 被删后的审计查看。 */
    private String serverName;
    private String toolName;
    /** Tool 描述快照 —— 调用时保存,避免 tool 删除/重写后审计看不懂。 */
    private String toolDescription;
    /** 传输协议(HTTP / SSE / STDIO)—— DB 落库的真实 transport,跟 mcp_backend 同列对齐。 */
    private String transportType;
    /** 调用方 session —— imawx-mcp-gateway 自己发的 HTTP session id。 */
    private String inboundSessionId;
    /** 被调方 session —— MCP server 给我们的 session id(反射从 transport 拿)。 */
    private String outboundSessionId;
    private String argumentsJson;
    private String resultJson;
    /** SSE 流式 logging/progress 事件合并 —— 一个 callTool 多 events 合一条 log。 */
    private String streamLogsJson;
    /** SUCCESS / FAILED / TIMEOUT。 */
    private String status;
    /** 1 成功 / 0 失败 —— DB 原值,前端一般用不到,保留做兼容。 */
    private Integer success;
    private String errorCode;
    private String errorMessage;
    private Integer costMs;
    /** 调用时间 —— DB 字段叫 create_time,VO 叫 invokedAt 跟前端契约对齐。 */
    private LocalDateTime invokedAt;
    /**
     * 调用方 IP(2026-07-02 加)—— 溯源/审计用。{@code IPv4}/{@code IPv6} 都兼容。
     */
    private String clientIp;
    /**
     * 调用方 User-Agent(2026-07-02 加)—— MCP 客户端标识(MCP Inspector / Claude Desktop / 自研 agent 等),
     * 前端表格可截断显示 + tooltip 完整。
     */
    private String userAgent;
}
