package com.imawx.mcp.gateway.service.mcpproxy;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.security.RsaOaepCipher;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.entity.dto.McpBackendCreateDTO;
import com.imawx.mcp.gateway.entity.dto.McpBackendQueryDTO;
import com.imawx.mcp.gateway.entity.dto.McpBackendUpdateDTO;
import com.imawx.mcp.gateway.entity.enums.ConnectionStatusEnum;
import com.imawx.mcp.gateway.entity.vo.McpAvailableBackendVO;
import com.imawx.mcp.gateway.entity.vo.McpAvailableBackendVO.McpAvailableToolVO;
import com.imawx.mcp.gateway.entity.vo.McpBackendVO;
import com.imawx.mcp.gateway.mapper.McpBackendMapper;
import com.imawx.mcp.gateway.mapper.McpBackendMapper.McpBackendQuery;
import com.imawx.mcp.gateway.mapper.McpBackendToolMapper;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.McpTransportDescriptorService;
import tools.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 外部 MCP server Service：CRUD、状态更新和前端展示模型转换。
 *
 * <p>企业内部部署下，所有登录用户共享同一份 {@code mcp_backend} 池；服务只记录
 * {@code createdBy}/{@code updatedBy} 用于审计，不按用户隔离 backend。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpBackendService {

    private final McpBackendMapper backendMapper;
    private final McpBackendToolMapper backendToolMapper;
    private final McpGatewayProperties properties;
    private final McpBackendExtensionService extensionService;
    private final BuiltinMcpDefinitionService definitionService;
    private final McpTransportDescriptorService transportDescriptorService;
    /**
     * 对 {@code mcp_backend.auth_token} 做列级加密，避免下游鉴权凭证明文落库。
     */
    private final RsaOaepCipher cipher;

    /**
     * RSA-OAEP(SHA-256)单块明文上限(字节):RSA-4096 = 512 - 2*32(hash) - 2 = 446。
     * auth_token 明文超过此长度加密会失败,提前拦成友好错误。绝大多数 bearer token / API key
     * 远小于 446 字节;若真需要存超长凭证(如大 JWT),得改混合加密(AES data key + RSA wrap)。
     */
    private static final int MAX_TOKEN_BYTES = 446;

    /**
     * 新建 backend —— 写 createdBy 创建人 userId。
     *
     * @param currentUserId 当前登录用户 userId,作为 createdBy 落库
     * @param dto           入参
     * @return 新建记录 id
     */
    @Transactional
    public Long create(Long currentUserId, McpBackendCreateDTO dto) {
        validate(dto);
        String backendId = dto.getBackendId();
        if (backendId == null || backendId.isBlank()) {
            backendId = generatedBackendId(dto.getTransportType());
        }
        if (backendMapper.selectByBackendId(backendId) != null) {
            throw new BizException(BizErrorCode.CONFLICT, "backendId 已存在: " + backendId);
        }
        McpBackendDO d = toDO(backendId, dto);
        d.setCreatedBy(currentUserId);
        applyDefaults(d);
        backendMapper.insert(d);
        upsertExtensionIfNeeded(d.getId(), d.getTransportType(), dto.getExtraConfig(), dto.getAuthToken());
        log.info("[backend-create] id={} createdBy={} backendId={} transport={}",
                d.getId(), currentUserId, d.getBackendId(), d.getTransportType());
        return d.getId();
    }

    /**
     * 更新 backend —— 写 updatedBy 修改人 userId。
     *
     * @param currentUserId 当前登录用户 userId,作为 updatedBy 落库
     * @param dto           入参
     */
    @Transactional
    public void update(Long currentUserId, McpBackendUpdateDTO dto) {
        if (dto.getId() == null) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "id 不能为空");
        }
        McpBackendDO existed = getById(dto.getId());
        McpBackendDO u = new McpBackendDO();
        BeanUtils.copyProperties(dto, u, "tags", "authToken"); // tags/authToken 单独处理
        u.setId(existed.getId());
        u.setBackendId(existed.getBackendId()); // backendId 不可改
        u.setCreatedBy(existed.getCreatedBy()); // createdBy 不可改
        u.setUpdatedBy(currentUserId);
        // 编辑时留空表示不修改；重填时加密后覆盖。
        u.setAuthToken(encryptAuthToken(dto.getAuthToken()));
        validateBackendTarget(
                dto.getTransportType() == null ? existed.getTransportType() : dto.getTransportType(),
                dto.getEndpoint() == null ? existed.getEndpoint() : dto.getEndpoint());
        validateExtensionPayload(
                dto.getTransportType() == null ? existed.getTransportType() : dto.getTransportType(),
                dto.getExtraConfig(),
                dto.getAuthToken(),
                false);
        if (dto.getTags() != null) {
            u.setTags(serializeTags(dto.getTags()));
        }
        backendMapper.updateById(u);
        upsertExtensionIfNeeded(existed.getId(),
                dto.getTransportType() == null ? existed.getTransportType() : dto.getTransportType(),
                dto.getExtraConfig(), dto.getAuthToken());
        log.info("[backend-update] id={} updatedBy={} backendId={}",
                dto.getId(), currentUserId, existed.getBackendId());
    }

    @Transactional
    public void delete(Long id) {
        McpBackendDO existed = getById(id);
        backendMapper.deleteById(id);
        extensionService.deleteByBackendId(id);
        log.info("[backend-delete] id={} backendId={}", id, existed.getBackendId());
    }

    /**
     * 直接按 id 查全平台 backend；访问权限由 controller/admin 鉴权层控制。
     */
    public McpBackendDO getById(Long id) {
        McpBackendDO d = backendMapper.selectById(id);
        if (d == null) {
            throw new BizException(BizErrorCode.NOT_FOUND, "外部 MCP server 不存在: id=" + id);
        }
        return d;
    }

    /** 聚合路由用：拉所有启用的 backend，顺序由 mapper 保证稳定。 */
    public List<McpBackendDO> listEnabled() {
        return backendMapper.selectEnabled();
    }

    /**
     * Token 授权编辑 UI 用：返回所有 enabled backend 及其已同步 tool。
     *
     * <p>返回当前全平台 enabled 的 backend，每个 backend 嵌套它已同步过的 tool 列表。
     * 用 IN 查询一次拿所有 tool 避免 N+1。前端授权编辑 UI 据此渲染"backend / tool 二级勾选"。
     *
     * <p>无 backend 时返回空列表(不是 null)。
     */
    public List<McpAvailableBackendVO> listAvailableWithTools() {
        List<McpBackendDO> backends = listEnabled();
        if (backends.isEmpty()) return Collections.emptyList();

        // 一次 IN 查询所有 tool,按 backendId (Long) 分组
        List<Long> backendLongIds = backends.stream().map(McpBackendDO::getId).toList();
        Map<Long, List<McpAvailableToolVO>> toolsByBackendId =
                backendToolMapper.selectListByBackendIds(backendLongIds).stream()
                        .collect(Collectors.groupingBy(
                                com.imawx.mcp.gateway.entity.do_.McpBackendToolDO::getBackendId,
                                Collectors.mapping(t -> McpAvailableToolVO.builder()
                                        .name(t.getToolName())
                                        .description(t.getDescription())
                                        .build(), Collectors.toList())));

        return backends.stream()
                .map(b -> McpAvailableBackendVO.builder()
                        .backendId(b.getBackendId())
                        .serverName(b.getServerName())
                        .transportType(b.getTransportType())
                        .enabled(b.getEnabled())
                        .tools(toolsByBackendId.getOrDefault(b.getId(), Collections.emptyList()))
                        .build())
                .toList();
    }

    /** 聚合路由用：按 backendId 字符串查，找不到时返回 null。 */
    public McpBackendDO selectByBackendId(String backendId) {
        return backendMapper.selectByBackendId(backendId);
    }

    /** 后台列表页：查询全平台 backend。 */
    public PageResult<McpBackendVO> page(McpBackendQueryDTO q) {
        McpBackendQuery x = new McpBackendQuery();
        x.keyword = q.getKeyword();
        x.transportType = q.getTransportType();
        x.enabled = q.getEnabled();
        x.offset = (q.getPageNum() - 1) * q.getPageSize();
        x.size = q.getPageSize();
        List<McpBackendDO> records = backendMapper.selectPageList(x);
        long total = backendMapper.countByQuery(x);
        List<McpBackendVO> vos = records.stream().map(this::toVO).toList();
        return new PageResult<>(vos, total, q.getPageNum(), q.getPageSize());
    }

    /** 更新连接状态(test / sync / health-check 任务入口)。 */
    public void updateStatus(Long id, Integer status, String lastError, LocalDateTime at) {
        McpBackendDO u = new McpBackendDO();
        u.setId(id);
        u.setStatus(status);
        u.setLastError(lastError);
        u.setLastCheckAt(at);
        backendMapper.updateById(u);
    }

    public void updateSyncResult(Long id, Integer status, String lastSyncError,
                                 String toolsSnapshot, LocalDateTime at) {
        McpBackendDO u = new McpBackendDO();
        u.setId(id);
        u.setStatus(status);
        u.setLastSyncError(lastSyncError);
        u.setToolsSnapshot(toolsSnapshot);
        u.setLastSyncAt(at);
        backendMapper.updateById(u);
    }

    /** 直接按 id 更新任意字段(无所有权校验,service 内部用)。 */
    public void update(Long id, McpBackendDO patch) {
        patch.setId(id);
        backendMapper.updateById(patch);
    }

    /** 按 wrapper 更新(service 内部用,绕开 @TableLogic / 业务校验)。 */
    public void updateByWrapper(Long id, McpBackendDO patch) {
        backendMapper.update(patch, new LambdaUpdateWrapper<McpBackendDO>().eq(McpBackendDO::getId, id));
    }

    private void applyDefaults(McpBackendDO d) {
        if (d.getHealthInterval() == null) {
            d.setHealthInterval(properties.getMcp().getSync().getDefaultIntervalSeconds());
        }
        if (d.getFailThreshold() == null) {
            d.setFailThreshold(properties.getMcp().getSync().getDefaultFailThreshold());
        }
        if (d.getFailCount() == null) {
            d.setFailCount(0);
        }
        if (d.getEnabled() == null) {
            d.setEnabled(1);
        }
        if (d.getStatus() == null) {
            d.setStatus(ConnectionStatusEnum.DISCONNECTED.getCode());
        }
    }

    private void validate(McpBackendCreateDTO dto) {
        if (dto.getTransportType() == null) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "transportType 不能为空");
        }
        transportDescriptorService.validateTransport(dto.getTransportType());
        if (dto.getEndpoint() == null || dto.getEndpoint().isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "endpoint 不能为空");
        }
        validateBackendTarget(dto.getTransportType(), dto.getEndpoint());
        validateExtensionPayload(dto.getTransportType(), dto.getExtraConfig(), dto.getAuthToken(), true);
    }

    public void validateBackendTarget(String transportType, String endpoint) {
        transportDescriptorService.validateEndpoint(transportType, endpoint);
    }

    private void validateExtensionPayload(String transportType, String extraConfig, String authToken, boolean create) {
        if (!isExtensionTransport(transportType)) {
            return;
        }
        String type = transportType.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> cfg = parseExtraConfig(extraConfig, type);
        transportDescriptorService.validateExtensionConfig(type, cfg, authToken, create);
    }

    private static Map<String, Object> parseExtraConfig(String extraConfig, String type) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return Map.of();
        }
        Map<String, Object> cfg = JsonUtil.fromJson(extraConfig, new TypeReference<Map<String, Object>>() {});
        if (cfg == null) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, type + " extraConfig 必须是 JSON 对象");
        }
        return cfg;
    }

    /**
     * auth_token 加密助手 —— 明文 bearer token / API key → RSA-OAEP 密文 envelope {@code v1:<base64>}。
     *
     * <p>语义:
     * <ul>
     *   <li>{@code null} / blank → 返 {@code null}(create 表示不设置,update 表示"不修改"，
     *       MyBatis-Plus updateById 跳过 null 字段,保留已存密文)</li>
     *   <li>非空 → 长度校验(≤ {@link #MAX_TOKEN_BYTES} 字节)后 {@code cipher.encrypt}</li>
     * </ul>
     *
     * <p>读取(将来接下游鉴权 header)时用 {@code cipher.decrypt(backend.getAuthToken())} 还原。
     *
     * @param plain 明文 token(可能为 null / blank)
     * @return 密文 envelope 或 null
     * @throws BizException 明文超过 RSA-OAEP 单块上限
     */
    public String decryptAuthToken(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return cipher.decrypt(encrypted);
    }

    private String encryptAuthToken(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        int bytes = plain.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TOKEN_BYTES) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                    "auth token 过长: " + bytes + " 字节，RSA-OAEP 单块最大 " + MAX_TOKEN_BYTES + " 字节");
        }
        return cipher.encrypt(plain);
    }

    private McpBackendDO toDO(String backendId, McpBackendCreateDTO dto) {
        McpBackendDO d = new McpBackendDO();
        d.setBackendId(backendId);
        d.setServerName(dto.getServerName());
        d.setTransportType(dto.getTransportType().toUpperCase());
        d.setEndpoint(dto.getEndpoint());
        d.setAuthToken(isExtensionTransport(dto.getTransportType()) ? null : encryptAuthToken(dto.getAuthToken()));
        d.setExtraConfig(isExtensionTransport(dto.getTransportType()) ? null : dto.getExtraConfig());
        d.setTags(serializeTags(dto.getTags()));
        d.setRemark(dto.getRemark());
        d.setEnabled(dto.getEnabled());
        d.setHealthInterval(dto.getHealthInterval());
        d.setFailThreshold(dto.getFailThreshold());
        return d;
    }

    private static String generatedBackendId(String transportType) {
        String prefix = transportType == null || transportType.isBlank()
                ? "bk"
                : transportType.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void upsertExtensionIfNeeded(Long backendId, String transportType, String extraConfig, String authToken) {
        if (!isExtensionTransport(transportType)) {
            return;
        }
        Map<String, Object> config = JsonUtil.fromJson(extraConfig, new TypeReference<Map<String, Object>>() {});
        if (config == null) config = Map.of();
        String type = transportType.trim().toUpperCase();
        String secretKey = transportDescriptorService.secretKey(type);
        Map<String, Object> secret = new java.util.LinkedHashMap<>();
        if (authToken != null && !authToken.isBlank()) {
            secret.put(secretKey, authToken.trim());
        }
        for (String secretConfigKey : transportDescriptorService.secretConfigKeys(type)) {
            if (config.containsKey(secretConfigKey)) {
                Object secretValue = config.get(secretConfigKey);
                if (secretValue != null && !secretValue.toString().isBlank()) {
                    secret.put(secretConfigKey, secretValue.toString().trim());
                }
                config = new java.util.LinkedHashMap<>(config);
                config.remove(secretConfigKey);
            }
        }
        extensionService.upsert(backendId, transportType.toUpperCase(), config, secret);
    }

    private boolean isExtensionTransport(String transportType) {
        return transportDescriptorService.isExtensionTransport(transportType);
    }

    /**
     * tags 列表序列化为 JSON 字符串存进 DO。
     *
     * <p>语义:
     * <ul>
     *   <li>{@code null} → {@code null}(不覆盖,update 路径表示"不更新")</li>
     *   <li>空列表/全部 blank → {@code null}(视为清空,落库 NULL)</li>
     *   <li>非空 → trim + 去空 + 去重 + JSON 数组字符串</li>
     * </ul>
     */
    private static String serializeTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String t : tags) {
            if (t == null) continue;
            String v = t.trim();
            if (v.isEmpty()) continue;
            if (!cleaned.contains(v)) {
                cleaned.add(v);
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        return JsonUtil.toJson(cleaned);
    }

    /**
     * DO 里的 JSON 字符串反序列化为 List<String> 给前端消费。
     *
     * <p>容错:解析失败返回 {@code Collections.emptyList()},不让 VO 构造崩。
     */
    private static List<String> deserializeTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> parsed = JsonUtil.fromJson(json, new TypeReference<List<String>>() {});
        return parsed == null ? List.of() : parsed;
    }

    /**
     * DO 转 VO(列表 / 详情共用)。
     *
     * <p>toolCount 实时从 {@code mcp_backend_tool} COUNT —— 不在 mcp_backend 加冗余列,
     * 避免 sync 写状态时还得双写两张表(CP 下不必要)。N 条 backend 时 N 次 COUNT 开销可忽略。
     *
     */
    public McpBackendVO toVO(McpBackendDO d) {
        Integer toolCount = null;
        if (d.getId() != null) {
            try {
                toolCount = (int) backendToolMapper.countByBackendId(d.getId());
            } catch (Exception ignored) {
                // 异常不让 VO 构造崩 —— 没快照时返回 0 即可,UI 显示"尚未同步"
            }
        }
        if (isExtensionTransport(d.getTransportType())) {
            Integer declaredCount = definitionService.toolCount(d.getTransportType());
            if (declaredCount != null) {
                toolCount = declaredCount;
            }
        }
        String extraConfig = isExtensionTransport(d.getTransportType())
                ? JsonUtil.toJson(extensionService.config(d.getId()))
                : d.getExtraConfig();
        return McpBackendVO.builder()
                .id(String.valueOf(d.getId()))
                .backendId(d.getBackendId())
                .createdBy(d.getCreatedBy())
                .updatedBy(d.getUpdatedBy())
                .serverName(d.getServerName())
                .transportType(d.getTransportType())
                .endpoint(d.getEndpoint())
                .extraConfig(extraConfig)
                .tags(deserializeTags(d.getTags()))
                .remark(d.getRemark())
                .status(ConnectionStatusEnum.fromCode(d.getStatus()).getLabel())
                .enabled(d.getEnabled())
                .healthInterval(d.getHealthInterval())
                .failThreshold(d.getFailThreshold())
                .failCount(d.getFailCount())
                .lastCheckAt(d.getLastCheckAt())
                .lastError(d.getLastError())
                .lastSyncAt(d.getLastSyncAt())
                .lastSyncError(d.getLastSyncError())
                .toolCount(toolCount == null ? 0 : toolCount)
                .createTime(d.getCreateTime())
                .updateTime(d.getUpdateTime())
                .build();
    }

    public record PageResult<T>(List<T> records, long total, int pageNum, int pageSize) {
    }
}
