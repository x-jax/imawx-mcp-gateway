package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 协议 session(2026-07-02 阶段 3 改造加)—— 对应 MCP Streamable HTTP 标准
 * 的 {@code Mcp-Session-Id} 头。
 *
 * <p>每条记录是一次 {@code initialize} 握手的服务端状态,客户端拿到
 * {@code session_id} 后,所有后续请求带 {@code Mcp-Session-Id: <session_id>}
 * header —— server 据此反查 userId / tokenId / protocolVersion 等握手信息。
 *
 * <p>TTL 1h(可配),{@code last_active_at} 每次 method 调用刷新,
 * @Scheduled 每小时清过期 + state=CLOSED 的记录。
 *
 * <p>账号隔离:查询强绑 userId(从 token 鉴权拿到的 userId),不允许 A 的
 * session 被 B 用。
 */
@Data
@TableName("mcp_session")
public class McpSessionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 真正下发给客户端的 session id —— UUID v4(无 hyphen),MCP 协议标准
     * {@code Mcp-Session-Id} header 值。
     */
    private String sessionId;

    /**
     * 账号(从 token 鉴权拿到的 userId),账号隔离用。
     */
    private Long userId;

    /**
     * 鉴权用的 imwx_token.id(可空 —— 内部 admin session 不一定走 token,
     * 比如 console 调试场景)。
     */
    private Long tokenId;

    /**
     * 客户端 initialize 传过来的 protocolVersion,server 在响应里协商回相同值
     * (如不支持则回退到 server 默认支持的版本)。
     */
    private String protocolVersion;

    /**
     * clientInfo JSON 字符串(name / version),最大 512 字符。
     */
    private String clientInfo;

    /**
     * 状态机:
     * <ul>
     *   <li>INITIALIZED —— initialize 刚握手,客户端还没发 notifications/initialized</li>
     *   <li>ACTIVE —— 正常调用中</li>
     *   <li>CLOSED —— 客户端调 shutdown / 显式 close / 服务端 close</li>
     * </ul>
     */
    private String state;

    /** 最近一次 method 名(tools/list / tools/call ...)。 */
    private String lastMethod;

    private LocalDateTime createTime;
    /** 每次 method 调用刷新,配合 TTL 算 expire_at。 */
    private LocalDateTime lastActiveAt;
    /** TTL 过期时间 = lastActiveAt + 1h。 */
    private LocalDateTime expireAt;
    private LocalDateTime closeTime;
}
