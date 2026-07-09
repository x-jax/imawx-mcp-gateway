package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.entity.dto.McpLoginDTO;
import com.imawx.mcp.gateway.entity.dto.McpUserUpdateDTO;
import com.imawx.mcp.gateway.entity.vo.McpUserInfoVO;
import com.imawx.mcp.gateway.service.auth.McpUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证 Controller + 个人中心(2026-07-03 扩展,2026-07-04 TOTP 端点全部删除)。
 *
 * <p>认证路径(2026-07-04 简化):
 * <ul>
 *   <li>{@code POST /api/sys/auth/login}     —— 单步登录(account + password + TOTP code)。
 *       用户 secret 在 create 时自动生成,登录时三字段一起传。
 *       全局 TOTP 开启 + 用户 secret 为空 → 40103 "请联系管理员初始化两步验证"</li>
 *   <li>{@code POST /api/sys/auth/logout}    —— 登出</li>
 *   <li>{@code GET  /api/sys/auth/me}        —— 当前账号信息</li>
 * </ul>
 *
 * <p>个人中心(2026-07-04 砍 TOTP):
 * <ul>
 *   <li>{@code PUT  /api/sys/auth/me}            —— 改 displayName / email</li>
 *   <li>{@code PUT  /api/sys/auth/me/password}   —— 改密码(要校验旧密码)</li>
 *   <li><b>不暴露</b> {@code /api/sys/auth/me/totp/**} —— 个人不能"启用 / 重置 / 关闭" TOTP</li>
 * </ul>
 *
 * <p>理由:TOTP 由配置表({@code mcp.auth.totp-enabled})管总开关,密钥在 create 时自动生成,
 * 个人无权关闭 / 重置。被禁止的 4 个 端点彻底删除,而不是 disabled-by-flag,
 * 防止"心怀不轨的 UI 改造"重新启用。
 */
@Slf4j
@RestController
@RequestMapping("/api/sys/auth")
@RequiredArgsConstructor
public class SysAuthController {

    public static final String ACCESS_LOG_ACCOUNT_ATTR = "IMAWX_ACCESS_LOG_ACCOUNT";

    private final McpUserService userService;

    // ============================================================
    // 认证
    // ============================================================

    @GetMapping("/csrf")
    public R<CsrfTokenVO> csrf(CsrfToken token) {
        if (token == null) {
            return R.ok(new CsrfTokenVO("X-XSRF-TOKEN", "_csrf", ""));
        }
        return R.ok(new CsrfTokenVO(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @PostMapping("/login")
    public R<McpUserInfoVO> login(@Valid @RequestBody McpLoginDTO dto, HttpServletRequest request) {
        request.setAttribute(ACCESS_LOG_ACCOUNT_ATTR, dto.getAccount());
        log.info("[api] POST /api/sys/auth/login account={} hasTotp={}",
                dto.getAccount(), dto.getTotpCode() != null && !dto.getTotpCode().isBlank());
        McpUserInfoVO vo = userService.login(dto.getAccount(), dto.getPassword(), dto.getTotpCode());
        HttpSession session = request.getSession(true);
        userService.writeSessionAndVO(session, vo);
        request.setAttribute(SessionKeys.USER_ID, Long.parseLong(vo.getId()));
        request.setAttribute(ACCESS_LOG_ACCOUNT_ATTR, vo.getEmail());
        log.info("[auth-session] userId={} sessionId={}", vo.getId(), session.getId());
        return R.ok(vo);
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object uid = session == null ? null : session.getAttribute(SessionKeys.USER_ID);
        if (session != null) {
            session.invalidate();
        }
        log.info("[auth-logout] userId={}", uid);
        return R.ok();
    }

    @GetMapping("/me")
    public R<McpUserInfoVO> me(HttpSession session) {
        Object uid = session.getAttribute(SessionKeys.USER_ID);
        if (uid == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        return R.ok(userService.findById((Long) uid));
    }

    // ============================================================
    // 个人中心(2026-07-03 加,2026-07-04 砍 TOTP)
    // ============================================================

    /**
     * 改自己的 displayName / email。
     */
    @PutMapping("/me")
    public R<Void> updateMe(@RequestBody McpUserUpdateDTO dto, HttpSession session) {
        Long uid = currentUserId(session);
        // 非 admin 改自己时,忽略 status 字段(只允许改 displayName / email)
        if (dto.getStatus() != null && !McpUserService.isAdmin(uid)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "非管理员不能改自己状态");
        }
        userService.update(uid, uid, dto);
        return R.ok();
    }

    /**
     * 改自己密码 —— 要校验旧密码。
     */
    @PutMapping("/me/password")
    public R<Void> changeOwnPassword(@RequestBody Map<String, String> body, HttpSession session) {
        Long uid = currentUserId(session);
        String oldPassword = body == null ? null : body.get("oldPassword");
        String newPassword = body == null ? null : body.get("newPassword");
        if (oldPassword == null || oldPassword.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "oldPassword 不能为空");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "newPassword 不能为空");
        }
        userService.updateOwnPassword(uid, oldPassword, newPassword);
        session.setAttribute(SessionKeys.MUST_CHANGE_PASSWORD, Boolean.FALSE);
        return R.ok();
    }

    private static Long currentUserId(HttpSession session) {
        Object v = session.getAttribute(SessionKeys.USER_ID);
        if (v == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) return Long.parseLong(s);
        throw new BizException(BizErrorCode.INTERNAL_ERROR, "userId 类型异常");
    }

    public record CsrfTokenVO(String headerName, String parameterName, String token) {
    }
}
