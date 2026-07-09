package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Token 授权 tool 中间表(2026-07-03 加)。
 *
 * <p>DB 表 {@code mcp_token_tool_authorization}。
 * 一个 token 可以被授权多个具体 tool(必须同时指定 backendId + toolName)——
 * 调 /mcp 标准协议的 tools/call 时,严格校验 (backendId, toolName) ∈ 授权列表,
 * 否则返 403。
 *
 * <p>复合主键候选:(token_id, backend_id, tool_name)—— 用 UNIQUE 索引保证。
 * 不暴露 DB id 给前端,前端只用 token_id + backendId + toolName 三元组定位。
 *
 * <p>语义对比:
 * <ul>
 *   <li>{@code mcp_token_backend_authorization} —— 粗粒度,授权整个 backend(可调该 backend 所有 tool)</li>
 *   <li>{@code mcp_token_tool_authorization} —— 细粒度,只授权该 backend 的某个具体 tool</li>
 * </ul>
 * 校验逻辑(严格模式):tool 在任一表中即通过 —— tool 级更优先(显式更具体)。
 */
@Data
@TableName("mcp_token_tool_authorization")
public class McpTokenToolAuthorizationDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tokenId;
    private String backendId;
    private String toolName;
    private LocalDateTime createdAt;
}