package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.entity.dto.McpApiTokenAuthorizationsUpdateDTO;
import com.imawx.mcp.gateway.entity.dto.McpApiTokenCreateDTO;
import com.imawx.mcp.gateway.entity.vo.McpApiTokenCreatedVO;
import com.imawx.mcp.gateway.entity.vo.McpApiTokenVO;
import com.imawx.mcp.gateway.entity.vo.McpAvailableBackendVO;
import com.imawx.mcp.gateway.service.auth.McpApiTokenService;
import com.imawx.mcp.gateway.service.auth.McpUserService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderRegistry;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderServer;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API Token 管理 Controller。
 *
 * <p>对应前端 {@code /api/sys/tokens/**}:
 * <ul>
 *   <li>{@code POST   /}                —— 创建 token,响应含明文(仅此一次)</li>
 *   <li>{@code GET    /}                —— 列出当前用户的所有 token(不含 hash,含授权范围)</li>
 *   <li>{@code POST   /{id}/revoke}    —— 撤销 token(软删,记录保留)</li>
 *   <li>{@code DELETE /{id}}            —— 硬删 token</li>
 *   <li>{@code PUT    /{id}/authorizations} —— 单独更新 token 授权(2026-07-03 加)</li>
 *   <li>{@code GET    /available-backends} —— 拉当前用户可授权 backend + tool 列表(2026-07-03 加,授权编辑 UI 用)</li>
 * </ul>
 *
 * <p>账号隔离:所有操作从 session 拿 userId,SQL WHERE 强绑 —— 用户只能管自己的 token。
 */
@Slf4j
@RestController
@RequestMapping("/api/sys/tokens")
@RequiredArgsConstructor
public class SysTokenController {

    private final McpApiTokenService tokenService;
    private final McpBackendService backendService;
    private final McpProviderRegistry providerRegistry;

    /**
     * 创建 token —— 返回明文 plaintext 字段,这是前端唯一能展示给用户的机会。
     *
     * @param dto     创建参数(用途 / scopes / 过期时间 / 授权)
     * @param session HTTP session
     * @return 创建后的 token(含明文)
     */
    @PostMapping
    public R<McpApiTokenCreatedVO> create(@Valid @RequestBody McpApiTokenCreateDTO dto,
                                            HttpSession session) {
        Long userId = currentUserId(session);
        McpUserService.requireAdmin(userId);
        log.info("[api] POST /api/sys/tokens userId={} name={} restrictMode={}",
                userId, dto.getName(), dto.getRestrictMode());
        return R.ok(tokenService.create(userId, dto));
    }

    /**
     * 列出当前用户的所有 token(不含 hash 字段,含授权范围)。
     *
     * @param session HTTP session
     * @return token 列表
     */
    @GetMapping
    public R<List<McpApiTokenVO>> list(HttpSession session) {
        Long userId = currentUserId(session);
        return R.ok(tokenService.list(userId));
    }

    /**
     * 撤销 token —— 软删({@code status=0} + revoked_at)。
     *
     * @param id      token 主键
     * @param session HTTP session
     */
    @PostMapping("/{id}/revoke")
    public R<Void> revoke(@PathVariable Long id, HttpSession session) {
        Long userId = currentUserId(session);
        tokenService.revoke(userId, id);
        return R.ok();
    }

    /**
     * 硬删 token —— DELETE FROM,不可恢复。
     *
     * @param id      token 主键
     * @param session HTTP session
     */
    @DeleteMapping("/{id}")
    public R<Void> hardDelete(@PathVariable Long id, HttpSession session) {
        Long userId = currentUserId(session);
        tokenService.hardDelete(userId, id);
        return R.ok();
    }

    /**
     * 单独更新 token 授权(2026-07-03 加)—— PUT /api/sys/tokens/{id}/authorizations。
     *
     * <p>不改 token 名 / 哈希 / 过期时间,纯改授权范围:
     * <ul>
     *   <li>{@code restrictMode}:null = 不改;非 null = 覆盖</li>
     *   <li>{@code authorizedBackends}:null = 不改;非 null(可空)= 完整替换</li>
     *   <li>{@code authorizedTools}:null = 不改;非 null(可空)= 完整替换</li>
     * </ul>
     */
    @PutMapping("/{id}/authorizations")
    public R<Void> updateAuthorizations(@PathVariable Long id,
                                         @RequestBody McpApiTokenAuthorizationsUpdateDTO dto,
                                         HttpSession session) {
        Long userId = currentUserId(session);
        McpUserService.requireAdmin(userId);
        log.info("[api] PUT /api/sys/tokens/{}/authorizations userId={}", id, userId);
        tokenService.updateAuthorizations(userId, id, dto);
        return R.ok();
    }

    /**
     * 拉可授权 backend + tool 列表(2026-07-03 改)—— token 授权编辑 UI 用。
     *
     * <p>2026-07-03 改:不再 userId 过滤 —— 项目不是 SaaS,所有 backend 全平台共享,
     * 任何用户的 token 都能选任何 backend 授权。
     *
     * <p>返回全平台全部 enabled backend,每个 backend 嵌套它已同步的 tool 列表。
     * 前端用这份数据渲染"backend / tool 二级勾选"UI —— 整个 backend 授权,还是精确到 tool。
     */
    @GetMapping("/available-backends")
    public R<List<McpAvailableBackendVO>> availableBackends(HttpSession session) {
        Long userId = currentUserId(session);
        McpUserService.requireAdmin(userId);
        log.info("[api] GET /api/sys/tokens/available-backends userId={}", userId);
        Map<String, McpAvailableBackendVO> merged = new LinkedHashMap<>();
        for (McpAvailableBackendVO backend : backendService.listAvailableWithTools()) {
            merged.put(backend.getBackendId(), backend);
        }
        for (McpProvider provider : providerRegistry.list()) {
            for (McpProviderServer server : provider.listEnabledServers()) {
                merged.put(server.serverId(), McpAvailableBackendVO.builder()
                        .backendId(server.serverId())
                        .serverName(server.name())
                        .transportType(server.serverType())
                        .enabled(server.enabled() ? 1 : 0)
                        .tools(provider.listTools(server.serverId()).stream()
                                .map(SysTokenController::toAvailableTool)
                                .toList())
                        .build());
            }
        }
        return R.ok(List.copyOf(merged.values()));
    }

    private static McpAvailableBackendVO.McpAvailableToolVO toAvailableTool(McpProviderTool tool) {
        return McpAvailableBackendVO.McpAvailableToolVO.builder()
                .name(tool.name())
                .description(tool.description())
                .disabled(false)
                .build();
    }

    private static Long currentUserId(HttpSession session) {
        Object uid = session.getAttribute(SessionKeys.USER_ID);
        if (uid == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        if (uid instanceof Long l) return l;
        if (uid instanceof Number n) return n.longValue();
        if (uid instanceof String s) return Long.parseLong(s);
        throw new BizException(BizErrorCode.INTERNAL_ERROR, "userId 类型异常: " + uid.getClass());
    }
}
