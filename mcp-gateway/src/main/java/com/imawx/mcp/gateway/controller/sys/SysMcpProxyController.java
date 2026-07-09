package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.core.McpClientExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.entity.do_.McpBackendToolDO;
import com.imawx.mcp.gateway.entity.do_.McpToolOverrideDO;
import com.imawx.mcp.gateway.entity.dto.McpBackendCreateDTO;
import com.imawx.mcp.gateway.entity.dto.McpBackendQueryDTO;
import com.imawx.mcp.gateway.entity.dto.McpBackendUpdateDTO;
import com.imawx.mcp.gateway.entity.dto.McpToolOverrideDTO;
import com.imawx.mcp.gateway.entity.enums.ConnectionStatusEnum;
import com.imawx.mcp.gateway.entity.vo.McpBackendVO;
import com.imawx.mcp.gateway.entity.vo.McpInvokeResultVO;
import com.imawx.mcp.gateway.entity.vo.McpToolVO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService.PageResult;
import com.imawx.mcp.gateway.service.mcpproxy.McpToolOverrideService;
import com.imawx.mcp.gateway.service.mcpproxy.McpToolSyncService;
import com.imawx.mcp.gateway.service.mcpproxy.McpToolSyncService.SyncOutcome;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService.BuiltinMcpTemplate;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService.BuiltinProbeDefinition;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderRegistry;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.openapi.OpenApiHttpApiMcpProvider;
import com.imawx.mcp.gateway.service.auth.McpUserService;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import com.imawx.mcp.gateway.common.util.ClientInfoUtil;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/** MCP 服务管理接口。 */
@Slf4j
@RestController
@RequestMapping("/api/sys/mcp-proxy")
@RequiredArgsConstructor
public class SysMcpProxyController {

    private final McpBackendService backendService;
    private final McpToolSyncService toolSyncService;
    private final McpClientExecutor clientExecutor;
    private final OpenApiHttpApiMcpProvider openApiProvider;
    private final McpProviderRegistry providerRegistry;
    private final McpToolOverrideService toolOverrideService;
    private final BuiltinMcpDefinitionService definitionService;
    private final McpGatewayProperties properties;

    @GetMapping("/templates")
    public R<List<BuiltinMcpTemplate>> templates(HttpSession session) {
        Long userId = currentUserId(session);
        log.info("[api] GET /api/sys/mcp-proxy/templates userId={}", userId);
        return R.ok(definitionService.templates());
    }

    @GetMapping
    public R<PageResult<McpBackendVO>> page(McpBackendQueryDTO q, HttpSession session) {
        Long userId = currentUserId(session);
        log.info("[api] GET /api/sys/mcp-proxy userId={} keyword={} transport={}",
                userId, q.getKeyword(), q.getTransportType());
        return R.ok(backendService.page(q));
    }

    @GetMapping("/{id}")
    public R<McpBackendVO> get(@PathVariable Long id, HttpSession session) {
        return R.ok(backendService.toVO(backendService.getById(id)));
    }

    @PostMapping
    public R<CreateResult> create(@Valid @RequestBody McpBackendCreateDTO dto, HttpSession session,
                                  HttpServletRequest request) {
        Long userId = currentUserId(session);
        String transportType = dto.getTransportType();
        if (transportType == null || transportType.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                    "transportType 不能为空（请明确 HTTP / SSE / STDIO / BUILTIN provider）");
        }
        backendService.validateBackendTarget(transportType, dto.getEndpoint());
        log.info("[api] POST /api/sys/mcp-proxy userId={} serverName={} transport={} endpoint={}",
                userId, dto.getServerName(), dto.getTransportType(), dto.getEndpoint());
        Long id = backendService.create(userId, dto);
        McpBackendDO created = backendService.getById(id);
        if (isBuiltinProviderTransport(created.getTransportType())) {
            int count = builtinProviderToolCount(created);
            markBuiltinSynced(created);
            return R.ok(new CreateResult(id, new SyncOutcome(true, count, null, null)));
        }
        McpToolSyncService.SyncOutcome sync = toolSyncService.sync(userId, id, session.getId(),
                clientIp(request),
                ClientInfoUtil.resolveUserAgent(request));
        return R.ok(new CreateResult(id, sync));
    }

    /** 新增/编辑表单探活，不写 tool 快照和调用日志。 */
    @PostMapping("/validate")
    public R<List<ToolPreview>> validate(@Valid @RequestBody McpBackendCreateDTO dto,
                                          HttpSession session) {
        Long userId = currentUserId(session);
        log.info("[api] POST /api/sys/mcp-proxy/validate userId={} transport={} endpoint={}",
                userId, dto.getTransportType(), dto.getEndpoint());
        backendService.validateBackendTarget(dto.getTransportType(), dto.getEndpoint());
        if (isBuiltinProviderTransport(dto.getTransportType())) {
            if ("OPENAPI".equalsIgnoreCase(dto.getTransportType())) {
                return R.ok(openApiProvider.previewTools(dto).stream()
                        .map(t -> new ToolPreview(t.name(), t.description(), t.inputSchema()))
                        .toList());
            }
            List<ToolPreview> declaredTools = previewBuiltinTools(dto.getTransportType());
            List<ToolPreview> probedTools = validateBuiltinProvider(dto, userId, declaredTools.isEmpty());
            return R.ok(declaredTools.isEmpty() ? probedTools : declaredTools);
        }
        McpBackendDO probe = new McpBackendDO();
        probe.setTransportType(dto.getTransportType());
        probe.setEndpoint(dto.getEndpoint());
        probe.setExtraConfig(dto.getExtraConfig());
        probe.setStatus(ConnectionStatusEnum.DISCONNECTED.getCode());
        probe.setFailCount(0);
        probe.setFailThreshold(3);

        List<McpSchema.Tool> tools = clientExecutor.withClient(probe, c -> c.listTools().tools()).result();
        List<ToolPreview> result = tools == null ? List.of()
                : tools.stream().map(t -> new ToolPreview(t.name(), t.description(),
                        com.imawx.mcp.gateway.common.util.JsonUtil.toJson(t.inputSchema()))).toList();
        return R.ok(result);
    }

    /**
     * 探活返回的 tool 预览(对齐前端 {@code ImawxMcpProxyToolPreview})。
     * record 而非 class —— 跟 listTools() 返回形态对齐,前端 TS interface 字段名匹配。
     */
    public record ToolPreview(String name, String description, String inputSchema) {
    }

    private boolean isBuiltinProviderTransport(String transportType) {
        return transportType != null && providerRegistry.find(transportType).isPresent();
    }

    private int builtinProviderToolCount(McpBackendDO backend) {
        return builtinProviderTools(backend).size();
    }

    private List<ToolPreview> builtinProviderTools(McpBackendDO backend) {
        List<ToolPreview> declared = previewBuiltinTools(backend.getTransportType());
        if (!declared.isEmpty()) return declared;
        return providerRegistry.find(backend.getTransportType())
                .map(provider -> provider.listTools(backend.getBackendId()).stream()
                        .map(this::toPreview)
                        .toList())
                .orElse(List.of());
    }

    private ToolPreview toPreview(McpProviderTool tool) {
        return new ToolPreview(tool.name(), tool.description(), tool.inputSchema());
    }

    private List<ToolPreview> previewBuiltinTools(String transportType) {
        return definitionService.tools(transportType).stream()
                .map(this::toPreview)
                .toList();
    }

    private List<ToolPreview> validateBuiltinProvider(McpBackendCreateDTO dto, Long userId, boolean loadToolsFromProvider) {
        Long id = null;
        try {
            id = backendService.create(userId, dto);
            McpBackendDO backend = backendService.getById(id);
            List<ToolPreview> providerTools = loadToolsFromProvider
                    ? providerRegistry.find(backend.getTransportType())
                    .map(provider -> provider.listTools(backend.getBackendId()).stream().map(this::toPreview).toList())
                    .orElse(List.of())
                    : List.of();
            BuiltinProbeDefinition probe = definitionService.probe(backend.getTransportType());
            providerRegistry.find(backend.getTransportType()).ifPresent(provider -> {
                if (probe != null && probe.toolName() != null && !probe.toolName().isBlank()) {
                    provider.callTool(new McpProviderCallRequest(
                            userId,
                            null,
                            backend.getTransportType(),
                            backend.getBackendId(),
                            probe.toolName(),
                            probe.safeArguments(),
                            "validate",
                            "127.0.0.1",
                            "mcp-proxy-validate"
                    ));
                }
            });
            return providerTools;
        } finally {
            if (id != null) {
                try {
                    backendService.delete(id);
                } catch (Exception e) {
                    log.warn("[builtin-validate-cleanup-fail] tempBackendId={} err={}", id, e.getMessage());
                }
            }
        }
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody McpBackendUpdateDTO dto,
                          HttpSession session) {
        Long userId = currentUserId(session);
        dto.setId(id);
        backendService.update(userId, dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, HttpSession session) {
        Long userId = currentUserId(session);
        log.info("[api] DELETE /api/sys/mcp-proxy/{} userId={}", id, userId);
        backendService.delete(id);
        return R.ok();
    }

    /** 同步远端 Tool 列表并更新服务状态。 */
    @PostMapping("/{id}/sync")
    public R<SyncOutcome> sync(@PathVariable Long id, HttpSession session,
                                 HttpServletRequest request) {
        Long userId = currentUserId(session);
        log.info("[api] POST /api/sys/mcp-proxy/{}/sync userId={} inboundSid={}", id, userId, session.getId());
        McpBackendDO backend = backendService.getById(id);
        if (isBuiltinProviderTransport(backend.getTransportType())) {
            int count = builtinProviderToolCount(backend);
            markBuiltinSynced(backend);
            return R.ok(new SyncOutcome(true, count, null, null));
        }
        return R.ok(toolSyncService.sync(userId, id, session.getId(),
                clientIp(request),
                ClientInfoUtil.resolveUserAgent(request)));
    }

    /** 读取已注册工具列表。 */
    @GetMapping("/{id}/tools")
    public R<List<McpToolVO>> listTools(@PathVariable Long id, HttpSession session) {
        Long userId = currentUserId(session);
        McpBackendDO backend = backendService.getById(id);
        Map<String, McpToolOverrideDO> overrides = toolOverrideService.mapByToolName(id);
        if (isBuiltinProviderTransport(backend.getTransportType())) {
            List<McpToolVO> vos = builtinProviderTools(backend).stream()
                    .map(t -> toToolVO(backend.getBackendId() + ":" + t.name(), t.name(), t.description(), t.inputSchema(),
                            null, overrides.get(t.name())))
                    .toList();
            return R.ok(vos);
        }
        List<McpBackendToolDO> tools = toolSyncService.listTools(id);
        log.info("[api] GET /api/sys/mcp-proxy/{}/tools userId={} count={}", id, userId, tools.size());
        List<McpToolVO> vos = tools.stream()
                .map(t -> toToolVO(String.valueOf(t.getId()), t.getToolName(), t.getDescription(), t.getInputSchema(),
                        t.getUpdateTime(), overrides.get(t.getToolName())))
                .toList();
        return R.ok(vos);
    }

    @GetMapping({"/{id}/tools/overrides", "/{id}/tool-overrides"})
    public R<List<McpToolOverrideDO>> listToolOverrides(@PathVariable Long id, HttpSession session) {
        currentUserId(session);
        return R.ok(toolOverrideService.list(id));
    }

    @PutMapping("/{id}/tools/override")
    public R<Void> saveToolOverride(@PathVariable Long id,
                                    @Valid @RequestBody McpToolOverrideDTO dto,
                                    HttpSession session) {
        return saveToolOverrideInternal(id, dto, session);
    }

    @PutMapping("/{id}/tool-overrides/{toolName}")
    public R<Void> saveToolOverrideByPath(@PathVariable Long id,
                                          @PathVariable String toolName,
                                          @Valid @RequestBody McpToolOverrideDTO dto,
                                          HttpSession session) {
        dto.setToolName(toolName);
        return saveToolOverrideInternal(id, dto, session);
    }

    private R<Void> saveToolOverrideInternal(Long id, McpToolOverrideDTO dto, HttpSession session) {
        Long userId = currentUserId(session);
        log.info("[api] PUT /api/sys/mcp-proxy/{}/tools/override userId={} toolName={} displayName={}",
                id, userId, dto.getToolName(), dto.getDisplayName());
        toolOverrideService.save(id, dto);
        return R.ok();
    }

    /** 测试单个 tool，并写入调用日志。 */
    @PostMapping("/{id}/tools/{toolName}/test")
    public R<McpInvokeResultVO> testTool(
            @PathVariable Long id,
            @PathVariable String toolName,
            @RequestBody(required = false) ToolCallPayload payload,
            HttpSession session,
            HttpServletRequest request
    ) {
        Long userId = currentUserId(session);
        Map<String, Object> arguments = payload == null ? Map.of() : (payload.args() == null ? Map.of() : payload.args());
        String originalToolName = toolOverrideService.resolveOriginalToolName(id, toolName);
        log.info("[api] POST /api/sys/mcp-proxy/{}/tools/{}/test userId={} originalToolName={} inboundSid={}",
                id, toolName, userId, originalToolName, session.getId());
        return R.ok(toolSyncService.testTool(userId, id, originalToolName, arguments, session.getId(),
                clientIp(request),
                ClientInfoUtil.resolveUserAgent(request)));
    }

    /** 流式测试单个 tool，实时推送 logging/progress/result/error 事件。 */
    @PostMapping(value = "/{id}/tools/{toolName}/test-stream", produces = "text/event-stream")
    public SseEmitter testToolStream(
            @PathVariable Long id,
            @PathVariable String toolName,
            @RequestBody(required = false) ToolCallPayload payload,
            HttpSession session,
            HttpServletRequest request
    ) {
        Long userId = currentUserId(session);
        Map<String, Object> arguments = payload == null ? Map.of() : (payload.args() == null ? Map.of() : payload.args());
        String originalToolName = toolOverrideService.resolveOriginalToolName(id, toolName);
        log.info("[api] POST /api/sys/mcp-proxy/{}/tools/{}/test-stream userId={} originalToolName={} inboundSid={}",
                id, toolName, userId, originalToolName, session.getId());
        return toolSyncService.testToolStream(userId, id, originalToolName, arguments, session.getId(),
                clientIp(request),
                ClientInfoUtil.resolveUserAgent(request));
    }

    private McpToolVO toToolVO(String id, String originalName, String description, String inputSchema,
                               LocalDateTime updateTime, McpToolOverrideDO override) {
        return McpToolVO.builder()
                .id(id)
                .name(toolOverrideService.displayName(originalName, override))
                .originalName(originalName)
                .description(toolOverrideService.description(description, override))
                .inputSchema(toolOverrideService.inputSchema(inputSchema, override))
                .updateTime(updateTime)
                .build();
    }

    /** Tool 调试请求体。 */
    public record ToolCallPayload(Map<String, Object> args) {
    }

    private static Long currentUserId(HttpSession session) {
        Object v = session.getAttribute(SessionKeys.USER_ID);
        if (v == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        Long userId;
        if (v instanceof Long l) {
            userId = l;
        } else if (v instanceof Number n) {
            userId = n.longValue();
        } else if (v instanceof String s) {
            userId = Long.parseLong(s);
        } else {
            throw new BizException(BizErrorCode.INTERNAL_ERROR, "userId 类型异常: " + v.getClass());
        }
        McpUserService.requireAdmin(userId);
        return userId;
    }

    /** 新增响应：返回记录 id 和同步结果。 */
    public record CreateResult(Long id, SyncOutcome sync) {
    }

    private void markBuiltinSynced(McpBackendDO backend) {
        backendService.updateSyncResult(
                backend.getId(),
                ConnectionStatusEnum.CONNECTED.getCode(),
                null,
                null,
                LocalDateTime.now()
        );
    }

    private String clientIp(HttpServletRequest request) {
        return ClientInfoUtil.resolveClientIp(request, properties.getSecurity().getTrustedProxyCidrs());
    }

}
