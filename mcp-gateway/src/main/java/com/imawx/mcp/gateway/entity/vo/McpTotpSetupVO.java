package com.imawx.mcp.gateway.entity.vo;

import lombok.Builder;
import lombok.Data;

/**
 * TOTP 重置响应 VO(2026-07-04 改名)—— 现阶段改名为"重置密钥"流程专用。
 *
 * <p>{@code secret} 和 {@code otpauthUri} 只在响应这一刻出现,前端用
 * {@code otpauthUri} 渲染二维码,admin 把 secret / QR 转给用户(用户丢 App 时用)。
 *
 * <p>重置后旧 secret 失效,verified_at 清空(下次 login 强制走 TOTP 校验,verify 通过再置上)。
 */
@Data
@Builder
public class McpTotpSetupVO {

    /** Base32 编码的 TOTP secret(160 bit)—— 用户手动输入时用。 */
    private String secret;

    /** 完整 otpauth:// URI —— 前端用 qrcode 库渲染成二维码图片。 */
    private String otpauthUri;
}
