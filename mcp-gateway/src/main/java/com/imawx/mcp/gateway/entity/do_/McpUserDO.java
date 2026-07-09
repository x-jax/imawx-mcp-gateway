package com.imawx.mcp.gateway.entity.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** {@code mcp_user} 表 DO。 */
@Data
@TableName("mcp_user")
public class McpUserDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希。 */
    private String passwordHash;

    private String displayName;

    private String email;

    /** 1=启用 / 0=禁用。 */
    private Integer status;

    /** 1=下次登录后必须修改密码。 */
    private Integer mustChangePassword;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ===== TOTP 2FA(2026-07-02 加)=====

    /**
     * TOTP secret(2026-07-04 重构)—— DB 存的是 RSA-OAEP 密文 envelope
     * {@code v1:<base64(RSA-OAEP ciphertext)>},不是明文 base32。
     * 解密走 {@code RsaOaepCipher.decrypt()},明文长度仍是 32 字符 base32
     * (160 bit,Google Authenticator 标准)。
     *
     * <p>改造原因:之前明文存,DB 泄露 → 所有用户 2FA secret 直接可读。
     * 现在走 column-level 加密,DB 泄露拿到的是密文,没有 RSA 私钥算不出 TOTP 码。
     * (列宽 VARCHAR(1024),RSA-4096 密文 base64 ≈ 684 字符 + v1: 前缀)</p>
     *
     * <p>{@code totp_enabled=0} 时为 null,用户启用 2FA 时生成。
     */
    private String totpSecret;

    /**
     * 用户首次 verify TOTP(输 6 位码)通过的时间 —— null = 没 verify 过(不会真正"启用" 2FA)。
     *
     * <p>2026-07-04 改:取代原 {@code totp_enabled} 派生标志。
     * <ul>
     *   <li>{@code totp_verified_at != null} = 用户已 verify 过 2FA,login 必须走密码 + TOTP</li>
     *   <li>{@code totp_verified_at == null} 但 {@code totp_secret != null} = setup 完成但未首次 verify,
     *       这种用户 login 仍要求 verify(首次 verify 通过会自动写入)</li>
     *   <li>{@code totp_secret == null} = 永远不可能 login 走 TOTP 路径(无 secret 可对)</li>
     * </ul>
     */
    private LocalDateTime totpVerifiedAt;
}
