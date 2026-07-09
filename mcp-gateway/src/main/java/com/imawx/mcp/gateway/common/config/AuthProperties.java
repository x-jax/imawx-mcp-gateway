package com.imawx.mcp.gateway.common.config;

import com.imawx.mcp.gateway.service.system.McpSystemConfigService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 认证相关配置(2026-07-02 改)——
 *
 * <p>TOTP 2FA 总开关从 yml 改成从 {@code mcp_system_config} 表读,key 是
 * {@code mcp.auth.totp-enabled}:
 * <ul>
 *   <li>DB 默认值 = 0(dev 不开 TOTP);生产部署前手动改 1</li>
 *   <li>admin 在"系统配置"页改完立即生效,不需要重启</li>
 *   <li>本类通过 {@link McpSystemConfigService} 读 DB 缓存值,
 *       避免每次登录查 DB</li>
 * </ul>
 *
 * <p>老 yml 配置 {@code mcp-gateway.auth.totp-enabled} 仍兼容(降级兜底,
 * DB 没 key 时读 yml 默认值)。这样:
 * <ul>
 *   <li>全新部署:走 yml 默认 + DB 启动时加载</li>
 *   <li>admin 改了 DB:实时生效(其他 service 重新查时拿到新值)</li>
 * </ul>
 *
 * <p>为了避免每次登录查 DB,这里用 {@link AtomicBoolean} 缓存当前值,
 * 启动完成后从 DB 同步一次;DB 改了再手动调 {@link #refresh()}
 * 刷新(简单轮询/事件都行)。
 */
@Slf4j
@Data
@Configuration
public class AuthProperties {

    /**
     * TOTP 2FA 总开关(2026-07-02 改)——
     * <p>默认值 {@code false}(dev 友好);生产部署前在"系统配置"页改 {@code mcp.auth.totp-enabled=1}。
     * 本字段在应用 ready 后覆盖为 DB 缓存值。
     */
    private volatile boolean totpEnabled = false;

    private final McpSystemConfigService configService;

    public AuthProperties(McpSystemConfigService configService) {
        this.configService = configService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public void initAfterConfigCacheReady() {
        refresh();
    }

    /**
     * 从 DB 缓存重新读最新值。admin 在"系统配置"页改了 key 之后,
     * 可以通过 {@code POST /api/sys/system/config/refresh} 触发本方法。
     */
    public void refresh() {
        boolean dbValue = configService.getBoolean("mcp.auth.totp-enabled", false);
        if (this.totpEnabled != dbValue) {
            log.info("[auth-props] totp-enabled {} -> {}", this.totpEnabled, dbValue);
        }
        this.totpEnabled = dbValue;
    }
}
