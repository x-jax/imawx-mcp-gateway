package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Token 授权 backend 中间表(2026-07-03 加)。
 *
 * <p>DB 表 {@code mcp_token_backend_authorization}。
 * 一个 token 可以被授权多个 backend —— 调 /mcp 标准协议时,只能 listTools
 * 看到这些 backend 的 tool,callTool 时也校验 backendId 在授权列表内。
 *
 * <p>{@code backendId} 是 mcp_backend.backend_id 字符串别名(bk_xxx),不是 DB BIGINT。
 * 这样跨 user / 跨环境迁移 token 授权时,backend_id 字符串唯一性更高。
 *
 * <p>{@code createdAt} 加索引加速按时间排序的列表查询(目前没用到,预留给审计)。
 */
@Data
@TableName("mcp_token_backend_authorization")
public class McpTokenBackendAuthorizationDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tokenId;
    private String backendId;
    private LocalDateTime createdAt;
}