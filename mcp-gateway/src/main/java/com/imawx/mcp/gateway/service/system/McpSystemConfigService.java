package com.imawx.mcp.gateway.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.security.SensitiveDataMasker;
import com.imawx.mcp.gateway.entity.do_.McpSystemConfigDO;
import com.imawx.mcp.gateway.entity.dto.McpSystemConfigDTO;
import com.imawx.mcp.gateway.entity.vo.McpSystemConfigVO;
import com.imawx.mcp.gateway.mapper.McpSystemConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置服务(2026-07-02 加)——
 *
 * <p>提供配置读写 + 内存缓存(供运行时访问),用 {@link ConcurrentHashMap}
 * 缓存以便 AuthProperties 等其他 service 实时读最新值,不重启。
 *
 * <p>写配置时:
 * <ol>
 *   <li>DB 覆盖式 upsert</li>
 *   <li>更新内存缓存</li>
 *   <li>日志记录(谁改的、什么值)</li>
 * </ol>
 *
 * <p>读配置:
 * <ul>
 *   <li>缓存有 → 返缓存</li>
 *   <li>缓存没 → 返回默认值</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpSystemConfigService {

    private final McpSystemConfigMapper mapper;

    /**
     * 内存缓存 —— 启动时从 DB 加载,后续所有读取走这里(避免每次查 DB)。
     * 写操作 DB 后同步更新缓存。
     */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void initAfterDdl() {
        reloadCache();
    }

    /**
     * 重新从 DB 加载所有配置到内存缓存。
     * (可以手动调,也作为 @Scheduled 周期性同步兜底)
     */
    public void reloadCache() {
        try {
            List<McpSystemConfigDO> all = mapper.listAll();
            for (McpSystemConfigDO row : all) {
                cache.put(row.getConfigKey(), row.getConfigValue());
            }
            refreshSecurityRuntimeConfig();
            log.info("[sys-config] cache reloaded, size={}", cache.size());
        } catch (Exception e) {
            log.warn("[sys-config] reloadCache failed: {}", e.getMessage());
        }
    }

    /** 列出所有配置(给 admin UI 用) */
    public List<McpSystemConfigVO> listAll() {
        List<McpSystemConfigDO> all = mapper.listAll();
        return all.stream().map(this::toVO).toList();
    }

    /**
     * 按 key 拿值(运行时其他 service 用)—— 优先走缓存。
     *
     * @return 配置值;key 不存在返 null
     */
    public String get(String key) {
        return cache.get(key);
    }

    public String getOrDefault(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public int getInt(String key, int defaultValue) {
        String v = cache.get(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 覆盖式写入:存在 update,不存在 insert。
     */
    @Transactional
    public void upsert(McpSystemConfigDTO dto, String updatedBy) {
        McpSystemConfigDO existing = mapper.selectById(dto.getConfigKey());
        McpSystemConfigDO row = new McpSystemConfigDO();
        row.setConfigKey(dto.getConfigKey());
        row.setConfigValue(dto.getConfigValue());
        row.setDescription(dto.getDescription());
        row.setUpdatedBy(updatedBy);
        row.setUpdateTime(LocalDateTime.now());

        if (existing == null) {
            row.setCreateTime(LocalDateTime.now());
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        // 同步内存缓存(其他 service 立即生效)
        cache.put(dto.getConfigKey(), dto.getConfigValue());
        refreshSecurityRuntimeConfig();
        log.info("[sys-config] upsert key={} value={} by={}",
                dto.getConfigKey(), SensitiveDataMasker.redactText(dto.getConfigValue()), updatedBy);
    }

    /** 删除配置 —— admin 一般不用,留接口给将来的"重置"功能 */
    public void delete(String key) {
        // 不允许删除关键 key
        if ("mcp.global.enabled".equals(key) || "mcp.auth.totp-enabled".equals(key)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "关键 key 不允许删除");
        }
        mapper.deleteById(key);
        cache.remove(key);
        refreshSecurityRuntimeConfig();
        log.info("[sys-config] delete key={}", key);
    }

    private void refreshSecurityRuntimeConfig() {
        SensitiveDataMasker.configure(
                cache.get(SensitiveDataMasker.CONFIG_REDACT_KEYS),
                cache.get(SensitiveDataMasker.CONFIG_REDACT_PATTERNS));
    }

    private McpSystemConfigVO toVO(McpSystemConfigDO row) {
        McpSystemConfigVO vo = new McpSystemConfigVO();
        vo.setConfigKey(row.getConfigKey());
        vo.setConfigValue(row.getConfigValue());
        vo.setDescription(row.getDescription());
        vo.setUpdatedBy(row.getUpdatedBy());
        vo.setUpdatedAt(row.getUpdateTime());
        return vo;
    }
}
