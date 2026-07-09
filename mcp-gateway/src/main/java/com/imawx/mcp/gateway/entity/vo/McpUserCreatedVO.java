package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 创建用户响应 VO(对应前端 {@code ImawxUserCreated},2026-07-04 加)。
 *
 * <p>设计原则(2026-07-04):
 * <ul>
 *   <li>TOTP secret 是协议要求 per-user 的密钥,创建时自动生成(不允许"创建后 manual 配")</li>
 *   <li>admin 创建新用户后,<b>这一次响应</b>返回明文 secret + otpauth URI,前端展示给 admin</li>
 *   <li>admin 有责任把 secret / QR 转给用户(线下 IM / 邮件 / 当面),用户拿这个去 Authenticator 扫码</li>
 *   <li>关掉弹窗后不再展示,丢了找 admin 重置 → 走 {@code POST /api/sys/users/{id}/totp/reset}</li>
 * </ul>
 *
 * <p>TOTP 协议本身不允许"系统级单一密钥",必须 per-user,所以这个 VO 含密钥字段,
 * 跟"配置表管 TOTP 总开关"(看 {@code mcp.auth.totp-enabled})是两个独立维度:
 * 开关在配置表,密钥在 user 表,这是 TOTP 协议决定的,不是冗余设计。
 */
@Data
@Builder
public class McpUserCreatedVO {

    /** 数据库主键 ID(string 输出避免 JS 精度丢失)。 */
    private String id;

    private String username;

    private String displayName;

    private String email;

    /** 1=启用 / 0=禁用。 */
    private Integer status;

    /**
     * 明文 TOTP secret(base32,32 字符)。
     * 仅本次响应可见,丢失找 admin 重置。
     */
    private String totpSecret;

    /**
     * otpauth:// URI,前端用 QR 库渲染成二维码,用户用 Authenticator App 扫码。
     */
    private String totpOtpauthUri;
}
