package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.AuthProperties;
import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.entity.dto.McpSystemConfigDTO;
import com.imawx.mcp.gateway.entity.vo.McpSystemConfigVO;
import com.imawx.mcp.gateway.service.system.McpSystemConfigService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统配置 Controller(2026-07-02 加)——
 *
 * <p>对接前端 {@code /system/config} 路由(组件已存在),提供:
 * <ul>
 *   <li>{@code GET /api/sys/system/config} —— 列出所有配置项</li>
 *   <li>{@code PUT /api/sys/system/config} —— 覆盖式写入(存在 update / 不存在 insert)</li>
 *   <li>{@code POST /api/sys/system/config/refresh} —— 触发 {@link AuthProperties#refresh()},
 *       让 AuthProperties 重新从 DB 读最新值(主要给 TOTP 开关改了之后立即生效)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/sys/system/config")
@RequiredArgsConstructor
public class SysSystemConfigController {

    private final McpSystemConfigService configService;
    private final AuthProperties authProperties;

    @GetMapping
    public R<List<McpSystemConfigVO>> list() {
        return R.ok(configService.listAll());
    }

    @PutMapping
    public R<Void> upsert(@Valid @RequestBody McpSystemConfigDTO dto, HttpSession session) {
        String updatedBy = currentUsername(session);
        log.info("[api] PUT /api/sys/system/config key={} value={} by={}",
                dto.getConfigKey(), dto.getConfigValue(), updatedBy);
        configService.upsert(dto, updatedBy);
        return R.ok();
    }

    /**
     * 触发 AuthProperties 重新从 DB 读最新值(2026-07-02 加)——
     * admin 在 UI 改了 TOTP 开关后,点"应用变更"调这个端点,无需重启后端
     * 就让新配置生效。
     */
    @PostMapping("/refresh")
    public R<Void> refresh(HttpSession session) {
        String updatedBy = currentUsername(session);
        log.info("[api] POST /api/sys/system/config/refresh by={}", updatedBy);
        authProperties.refresh();
        return R.ok();
    }

    private String currentUsername(HttpSession session) {
        Object uid = session.getAttribute(SessionKeys.USER_ID);
        if (uid == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        return (String) session.getAttribute(SessionKeys.USERNAME);
    }
}
