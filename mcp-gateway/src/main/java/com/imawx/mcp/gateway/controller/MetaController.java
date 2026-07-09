package com.imawx.mcp.gateway.controller;

import com.imawx.mcp.gateway.common.response.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查 / 元信息 Controller。
 *
 * <p>对应 {@code /api/admin/meta/**}:
 * <ul>
 *   <li>{@code GET /ping} —— 探活,k8s liveness/readiness 用</li>
 *   <li>{@code GET /info} —— 应用元信息(version / env / active profile)</li>
 * </ul>
 *
 * <p>鉴权放行(由 Spring Security 全 permitAll,真正的 admin 校验在后续阶段补充)。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @Value("${spring.application.name:unknown}")
    private String appName;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${mcp-gateway.version:0.0.1-SNAPSHOT}")
    private String version;

    /**
     * 探活端点,返回 pong。
     *
     * @return pong
     */
    @GetMapping("/ping")
    public R<Map<String, String>> ping() {
        return R.ok(Map.of("ping", "pong"));
    }

    /**
     * 应用元信息。
     *
     * @return 应用名 / 版本 / 当前 profile
     */
    @GetMapping("/info")
    public R<Map<String, String>> info() {
        return R.ok(Map.of(
                "app", appName,
                "version", version,
                "profile", activeProfile
        ));
    }
}