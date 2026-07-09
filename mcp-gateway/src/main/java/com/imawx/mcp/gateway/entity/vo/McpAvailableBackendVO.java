package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Token 授权编辑 UI 用 —— 当前用户可授权的 backend 列表(2026-07-03 加)。
 *
 * <p>对应后端 {@code GET /api/sys/tokens/available-backends}:
 * <ul>
 *   <li>{@code backendId} 字符串别名(bk_xxx)—— 直接给前端展示/勾选用</li>
 *   <li>{@code tools} 该 backend 已同步的 tool 列表(token 授权选择 tool 时要用)</li>
 * </ul>
 *
 * <p>前端按 {@code tools} 是否为空决定是勾整个 backend 还是展开选单个 tool:
 * <ul>
 *   <li>{@code tools} 为空 —— backend 还没同步 tool,只能"整个 backend 授权"或干脆跳过</li>
 *   <li>{@code tools} 非空 —— 列出每个 tool 供勾选</li>
 * </ul>
 */
@Data
@Builder
public class McpAvailableBackendVO {

    /** backend 字符串别名(bk_xxx,给前端用)。 */
    private String backendId;
    /** 后端展示名(如 "MySQL Dev" / "Stream Server")。 */
    private String serverName;
    /** 传输类型:HTTP / SSE / STDIO。 */
    private String transportType;
    /** 1=启用 / 0=禁用。 */
    private Integer enabled;
    /** 已同步的 tool 列表(可能为空)。 */
    private List<McpAvailableToolVO> tools;

    /**
     * Token 授权可用的 tool(2026-07-03 加)。
     *
     * <p>对应当前 backend 下 mcp_backend_tool 一行。{@code disabled} 字段同步过来,
     * 前端展示时把 disabled=true 的 tool 灰掉不勾。
     */
    @Data
    @Builder
    public static class McpAvailableToolVO {
        /** tool 名(不含 method 前缀,如 stream_text / execute_sql)。 */
        private String name;
        private String description;
        /** 是否禁用 —— 后端 McpBackendDO.toolsSnapshot 里的 disabled 字段,默认 false。 */
        private Boolean disabled;
    }
}