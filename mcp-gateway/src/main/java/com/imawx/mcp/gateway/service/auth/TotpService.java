package com.imawx.mcp.gateway.service.auth;

import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * TOTP 二次验证服务(2026-07-02 加)—— 实现 RFC 6238 标准,30 秒周期 / 6 位数字 /
 * SHA1 算法,跟所有主流 authenticator app 兼容。
 *
 * <p>兼容测试覆盖:
 * <ul>
 *   <li>Google Authenticator (iOS / Android)</li>
 *   <li>Microsoft Authenticator (iOS / Android)</li>
 *   <li>Authy (iOS / Android / Desktop)</li>
 *   <li>1Password (含 TOTP 字段)</li>
 *   <li>Bitwarden / KeePassXC / KeePass</li>
 *   <li>阿里安全令牌 / 腾讯安全令牌 (国内主流)</li>
 *   <li>任何支持 otpauth:// URI 的实现</li>
 * </ul>
 *
 * <p>关键 RFC 参数:
 * <ul>
 *   <li>HOTP 算法: HMAC-SHA1(K, counter), 截断成 6 位数字</li>
 *   <li>TOTP = HOTP with counter = floor((now - T0) / X), T0=0, X=30 秒</li>
 *   <li>Secret 编码: Base32 (RFC 4648, no padding)</li>
 *   <li>OTP URI: otpauth://totp/Issuer:account?secret=...&issuer=...&period=30&digits=6&algorithm=SHA1</li>
 * </ul>
 *
 * <p>实现是手写,不用 dev.samstevens.totp 这种外部库 —— 30 行代码,
 * 没有外部依赖,实现跟 RFC 6238 严格对齐,所有测试用例可对照 RFC 测试向量验证。
 *
 * @author Liu,Dongdong
 * @since 2026-07-02
 */
@Slf4j
@Service
public class TotpService {

    /** TOTP 周期:30 秒(RFC 6238 推荐,所有主流 app 默认) */
    public static final int PERIOD_SECONDS = 30;

    /** TOTP 位数:6 位(主流 app 默认) */
    public static final int DIGITS = 6;

    /** T0 epoch:1970-01-01 00:00:00 UTC(RFC 6238 标准) */
    public static final long T0 = 0L;

    /** Secret bit 长度:160 bit = 20 字节 = 32 字符 Base32(RFC 4226 推荐) */
    private static final int SECRET_BIT_LENGTH = 160;
    private static final int SECRET_BYTE_LENGTH = SECRET_BIT_LENGTH / 8;
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 生成新的 160 bit TOTP secret(Base32 编码 32 字符,无 padding)。
     *
     * <p>SecureRandom 密码学随机,不是 {@code new Random()} —— 后者能预测。
     */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * 生成 otpauth:// URI —— Google Authenticator 扫码的标准格式。
     *
     * <p>举例:
     * {@code otpauth://totp/imawx-mcp-gateway:admin@imawx.local?secret=JBSWY3DPEHPK3PXP&issuer=imawx-mcp-gateway&period=30&digits=6&algorithm=SHA1}
     *
     * @param issuer    服务名(显示在 app 里),如 "imawx-mcp-gateway"
     * @param accountLabel 用户标识(显示在 app 里),如 "admin@imawx.local"
     * @param secret    Base32 编码的 secret
     * @return 完整 otpauth URI
     */
    public String buildOtpauthUri(String issuer, String accountLabel, String secret) {
        // otpauth label 必须保留 "issuer:account" 中间的冒号，issuer/account 各自做 URI component 编码。
        String encodedLabel = encodeUriComponent(issuer) + ":" + encodeUriComponent(accountLabel);
        String encodedIssuer = encodeUriComponent(issuer);
        return "otpauth://totp/" + encodedLabel
                + "?secret=" + secret
                + "&issuer=" + encodedIssuer
                + "&period=" + PERIOD_SECONDS
                + "&digits=" + DIGITS
                + "&algorithm=SHA1";
    }

    /** 当前时间窗口的 6 位验证码。只用于生产 bootstrap 文件，应用日志不要打印该值。 */
    public String currentCode(String secret) {
        return generateCode(secret, currentCounter());
    }

    /** 当前 TOTP 时间窗口结束时刻(epoch seconds)。 */
    public long currentCodeValidUntilEpochSeconds() {
        long now = System.currentTimeMillis() / 1000L;
        return ((now - T0) / PERIOD_SECONDS + 1) * PERIOD_SECONDS + T0;
    }

    /**
     * 验证用户输入的 6 位 TOTP code 是否正确。
     *
     * <p>容差:±1 个周期(±30 秒) —— 解决客户端时间稍微偏移的问题。
     * RFC 6238 推荐 ±1,RFC 文档原文:"A validation value V is valid if V is
     * equal to T for any of the one or more consecutive counter values".
     */
    public boolean verify(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }
        // 兼容带空格 / 中划线的输入
        String normalized = code.replaceAll("[\\s-]", "");
        if (!normalized.matches("\\d{6}")) {
            return false;
        }
        long counter = (System.currentTimeMillis() / 1000L - T0) / PERIOD_SECONDS;
        for (int i = -1; i <= 1; i++) {
            String expected = generateCode(secret, counter + i);
            if (constantTimeEquals(expected, normalized)) {
                return true;
            }
        }
        return false;
    }

    private static long currentCounter() {
        return (System.currentTimeMillis() / 1000L - T0) / PERIOD_SECONDS;
    }

    /**
     * 给定 secret + counter,生成 6 位 TOTP code(测试用,也用于 verify 内部)。
     */
    public String generateCode(String base32Secret, long counter) {
        try {
            byte[] key = base32Decode(base32Secret);
            byte[] data = new byte[8];
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (counter & 0xff);
                counter >>>= 8;
            }
            // HMAC-SHA1
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(data);
            // 动态截断 RFC 4226 §5.3
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, DIGITS);
            // 补齐 6 位
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            log.error("[totp] generateCode failed: {}", e.getMessage());
            throw new BizException(BizErrorCode.INTERNAL_ERROR, "TOTP 生成失败");
        }
    }

    /**
     * 生成二维码内容(返回 otpauth URI,客户端用 QR 库渲染成图)。
     *
     * <p>后端不直接出 QR 图片 —— 前端用 qrcode.js 渲染 otpauth URI 成图,
     * 避免后端引入 zxing 这种重依赖。
     */
    public String generateQrPayload(String issuer, String accountLabel, String secret) {
        return buildOtpauthUri(issuer, accountLabel, secret);
    }

    // ===== 内部工具:Base32 编码 / 常数时间比较 =====

    /**
     * Base32 编码(RFC 4648, no padding) —— Google Authenticator secret 格式。
     */
    static String base32Encode(byte[] data) {
        char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
        if (data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int idx = (buffer >> (bitsLeft - 5)) & 0x1f;
                sb.append(alphabet[idx]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            int idx = (buffer << (5 - bitsLeft)) & 0x1f;
            sb.append(alphabet[idx]);
        }
        return sb.toString();
    }

    /**
     * Base32 解码(RFC 4648, 容错:小写 + 忽略 padding + 忽略空格)。
     */
    static byte[] base32Decode(String input) {
        String s = input.toUpperCase().replaceAll("=+$", "").replaceAll("\\s", "");
        int outLen = s.length() * 5 / 8;
        byte[] out = new byte[outLen];
        int buffer = 0;
        int bitsLeft = 0;
        int idx = 0;
        for (char c : s.toCharArray()) {
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= '2' && c <= '7') v = c - '2' + 26;
            else throw new IllegalArgumentException("Invalid Base32 char: " + c);
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private static String encodeUriComponent(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /**
     * 常数时间字符串比较(防 timing attack)。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
