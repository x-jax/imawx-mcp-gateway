package com.imawx.mcp.gateway.controller.sys;

import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.response.R;
import com.imawx.mcp.gateway.entity.dto.McpUserCreateDTO;
import com.imawx.mcp.gateway.entity.dto.McpUserQueryDTO;
import com.imawx.mcp.gateway.entity.dto.McpUserUpdateDTO;
import com.imawx.mcp.gateway.entity.vo.McpTotpSetupVO;
import com.imawx.mcp.gateway.entity.vo.McpUserCreatedVO;
import com.imawx.mcp.gateway.entity.vo.McpUserInfoVO;
import com.imawx.mcp.gateway.entity.vo.McpUserListVO;
import com.imawx.mcp.gateway.service.auth.McpUserService;
import com.imawx.mcp.gateway.service.auth.McpUserService.PageResult;
import com.imawx.mcp.gateway.service.auth.McpUserService.TotpStatusVO;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户管理 Controller(2026-07-03 加,2026-07-04 TOTP 路径精简)—— admin only。
 *
 * <p>对应前端 {@code /api/sys/users/**}。所有端点要求 admin(userId==1)权限,
 * 权限校验在 {@link McpUserService} 层做(controller 拿 session 传 currentUserId)。
 *
 * <p>账号不允许删除 —— 本 controller 故意不暴露 DELETE 端点。
 * 需求 2026-07-03 明确:"账号不允许删除" —— 物理层面就不给删的能力。
 *
 * <p>管理员(id=1)不允许被禁用 —— {@link McpUserService#update} 抛 FORBIDDEN 保护。
 * 前端按钮的 enable/disable 根据 {@link McpUserListVO#getIsAdmin} 渲染。
 *
 * <p><b>TOTP 设计闭环(2026-07-04):</b>
 * <ul>
 *   <li>{@code POST /} 创建用户 —— service 自动生成 totp_secret + 响应返明文 + otpauth</li>
 *   <li>{@code POST /{id}/totp/reset} 重置密钥(原 setup)—— 用户丢 App 时用</li>
 *   <li>{@code GET /{id}/totp} 查状态</li>
 *   <li><b>不暴露</b> {@code DELETE /{id}/totp} —— 不允许 UI 关闭 TOTP(配置表管全局)</li>
 *   <li><b>不暴露</b> {@code POST /{id}/totp/verify} —— 用户首登时 login 自动 verify,无需单独 verify 端点</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/sys/users")
@RequiredArgsConstructor
public class SysUserController {

    private final McpUserService userService;

    /**
     * 用户列表(分页 + 搜索)—— admin only。
     */
    @GetMapping
    public R<PageResult<McpUserListVO>> page(McpUserQueryDTO q, HttpSession session) {
        Long currentUserId = currentUserId(session);
        return R.ok(userService.page(currentUserId, q));
    }

    /**
     * 用户详情 —— admin only(非 admin 想看自己信息走 {@code /api/sys/auth/me})。
     */
    @GetMapping("/{id}")
    public R<McpUserInfoVO> detail(@PathVariable Long id, HttpSession session) {
        Long currentUserId = currentUserId(session);
        McpUserService.requireAdmin(currentUserId);
        return R.ok(userService.findById(id));
    }

    /**
     * 创建用户 —— admin only。
     *
     * <p><b>2026-07-04 改:</b>响应改为 {@link McpUserCreatedVO},含一次性明文 totp_secret + otpauth。
     * admin 拿到后转给用户扫码。
     */
    @PostMapping
    public R<McpUserCreatedVO> create(@Valid @RequestBody McpUserCreateDTO dto, HttpSession session) {
        Long currentUserId = currentUserId(session);
        return R.ok(userService.create(currentUserId, dto));
    }

    /**
     * 编辑用户(displayName / email / status)—— admin 可改任何人。
     *
     * <p>admin 不能被改 status=0(禁 admin)—— {@link McpUserService#update} 保护。
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody McpUserUpdateDTO dto,
                          HttpSession session) {
        Long currentUserId = currentUserId(session);
        userService.update(currentUserId, id, dto);
        return R.ok();
    }

    /**
     * 重置密码 —— admin only(用户自己改密码走 {@code /api/sys/auth/me/password})。
     */
    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id,
                                 @RequestBody Map<String, String> body,
                                 HttpSession session) {
        Long currentUserId = currentUserId(session);
        String newPassword = body == null ? null : body.get("newPassword");
        if (newPassword == null || newPassword.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "newPassword 不能为空");
        }
        userService.resetPassword(currentUserId, id, newPassword);
        return R.ok();
    }

    /**
     * 重置 TOTP 密钥(2026-07-04 改名 / 改语义)—— 用户丢 App 时由 admin 兜底。
     *
     * <p>覆盖旧 secret + 清 verified_at;响应一次性返明文 secret + otpauth URI。
     * admin 转给用户,用户用新密钥重新在 Authenticator 配,下次登录 verify 通过即重新启用。
     *
     * <p>不允许 admin 重置自己的密钥(防 lock out)—— service 层抛 FORBIDDEN。
     */
    @PostMapping("/{id}/totp/reset")
    public R<McpTotpSetupVO> totpReset(@PathVariable Long id, HttpSession session) {
        Long currentUserId = currentUserId(session);
        return R.ok(userService.resetTotp(currentUserId, id));
    }

    /**
     * 查 TOTP 状态(enabled / verifiedAt)—— 前端管理面板用。
     */
    @GetMapping("/{id}/totp")
    public R<TotpStatusVO> totpStatus(@PathVariable Long id, HttpSession session) {
        Long currentUserId = currentUserId(session);
        return R.ok(userService.getTotpStatus(currentUserId, id));
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
}
