package com.imawx.mcp.gateway.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * RSA-OAEP 加解密 util(2026-07-04 重构，前身是 AesGcmCipher）—— 给
 * {@code mcp_user.totp_secret} 这种"协议要求明文但 DB 不想裸奔"的字段做 column-level 加密。
 *
 * <p><b>为什么从 AES-256-GCM(对称)换成 RSA-OAEP(非对称)</b>：
 * 运维侧要求密钥用标准 PEM 格式、由 {@code openssl} 生成，方便 K8s secret /
 * 密钥管理系统托管与轮换。RSA 产出的是非对称密钥对，对称 AES 用不上，
 * 所以整条 cipher 换成 RSA-OAEP。TOTP secret 很小(base32 32 字符 ≈ 32 字节)，
 * 远小于 RSA-4096 OAEP 单块上限(≈ 446 字节)，一次 doFinal 就能加密，不需要
 * 混合(AES data key)方案。
 *
 * <p><b>算法选型</b>：
 * <ul>
 *   <li>RSA-4096(模数 4096 bit，NIST 长期强度)</li>
 *   <li>OAEP padding，hash = SHA-256，MGF1 = SHA-256（<b>显式</b>指定 MGF1 摘要，
 *       否则 JDK 默认 MGF1 用 SHA-1，跟 OAEP 主摘要 SHA-256 不一致，跨实现会解不开）</li>
 *   <li>JDK 25 {@code javax.crypto} + {@code java.security} 内置，无 BouncyCastle 依赖</li>
 * </ul>
 *
 * <p><b>密钥格式</b>：PKCS#8 PEM 私钥（{@code -----BEGIN PRIVATE KEY-----}）。
 * 生成命令(prod ops 手动跑)：
 * <pre>{@code  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out /etc/imawx/totp.key}</pre>
 * 只需要私钥文件；<b>公钥由 {@link #derivePublicKey} 从私钥的 CRT 参数(modulus + publicExponent)
 * 推导</b>，不额外读 {@code .pub}。
 *
 * <p><b>存储格式</b>（envelope，方便将来 key rotation）：
 * <pre>{@code  v1:<base64( RSA-OAEP ciphertext )>}</pre>
 * <ul>
 *   <li>前缀 {@code v1:} 标当前 key version，将来 rotate 到 v2 / v3 时
 *       decrypt 按前缀走不同 key，老数据不丢（沿用旧 envelope 约定，
 *       DB 里此前无残留密文，v1 直接指代 RSA-OAEP 方案）</li>
 *   <li>RSA-4096 密文固定 512 字节 → base64 ≈ 684 字符 → 加前缀 ≈ 687 字符，
 *       {@code mcp_user.totp_secret} 列需 VARCHAR(1024)</li>
 *   <li>OAEP padding 自带随机性：同一 key + 同一明文两次 encrypt 必返不同密文</li>
 * </ul>
 *
 * <p><b>安全边界</b>：本字段加密的威胁模型是"DB 被拖库但攻击者拿不到 app 主机私钥文件"。
 * 加解密都在同一进程，私钥常驻内存；非对称在此不比对称更"强"，价值在于密钥的标准化
 * 运维(PEM + openssl + secret 托管)。私钥文件权限务必 0600，且不得进 git。
 *
 * <p><b>用法</b>：
 * <pre>{@code
 *   String enc   = cipher.encrypt("JBSWY3DPEHPK3PXP...");   // → "v1:AbCdEf..."
 *   String plain = cipher.decrypt(enc);                     // → "JBSWY3DPEHPK3PXP..."
 * }</pre>
 */
@Slf4j
@Component
public class RsaOaepCipher {

    /** RSA-OAEP transformation：SHA-256 主摘要 + MGF1。 */
    private static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    /** 当前 envelope 版本前缀（将来 rotate 加 v2: / v3:）。 */
    public static final String VERSION_PREFIX_V1 = "v1:";

    /** 允许的最小模数长度(bit)——低于此拒绝，防止误用弱 key。 */
    private static final int MIN_KEY_BITS = 2048;

    /** PKCS#8 PEM 头尾标记。 */
    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

    /** 启动时由 {@link com.imawx.mcp.gateway.common.security.SecurityConfig} 注入。 */
    private PrivateKey privateKey;

    /** 从 {@link #privateKey} 的 CRT 参数推导，供 encrypt 用。 */
    private PublicKey publicKey;

    /**
     * 启动时调用一次，注入 PKCS#8 PEM 私钥字符串（{@code -----BEGIN PRIVATE KEY-----} 包裹）。
     *
     * @param pemPrivateKey PKCS#8 PEM 私钥文本（可含头尾行与换行）
     * @throws IllegalStateException 为空 / 非 RSA / 非 CRT 私钥 / 模数过短 / 解析失败
     */
    public void init(String pemPrivateKey) {
        if (pemPrivateKey == null || pemPrivateKey.isBlank()) {
            throw new IllegalStateException(
                    "RSA 私钥未配置（PKCS#8 PEM 不能为空）—— 检查 key 来源(env / file / dev 自动生成)");
        }
        PrivateKey priv = parsePkcs8Pem(pemPrivateKey);
        if (!(priv instanceof RSAPrivateKey rsaPriv)) {
            throw new IllegalStateException(
                    "私钥不是 RSA 类型: " + priv.getAlgorithm() + " —— 请用 openssl genpkey 生成 RSA 私钥（Ed25519 不能加密）");
        }
        int bits = rsaPriv.getModulus().bitLength();
        if (bits < MIN_KEY_BITS) {
            throw new IllegalStateException(
                    "RSA 模数过短: " + bits + " bit（最低 " + MIN_KEY_BITS + " bit）—— 用 openssl genpkey 重新生成 4096 bit RSA 私钥");
        }
        this.privateKey = priv;
        this.publicKey = derivePublicKey(priv);
        // 不打印任何 key 内容；只打模数长度和公钥 fingerprint 前缀，方便审计
        log.info("[rsa-oaep] cipher initialized, modulus = {} bit, padding = OAEP(SHA-256/MGF1-SHA-256), version = v1", bits);
    }

    /**
     * 加密任意字符串（典型用法：TOTP base32 secret）→ envelope 字符串 {@code v1:<base64>}。
     *
     * @param plaintext 明文（任意非空字符串，长度需 ≤ RSA-OAEP 单块上限）
     * @return envelope 字符串，DB 直接存这个
     * @throws IllegalArgumentException plaintext 为 null / 空
     * @throws IllegalStateException 加密失败（含明文超长）
     */
    public String encrypt(String plaintext) {
        ensureInitialized();
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("plaintext 不能为空");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams());
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX_V1 + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("RSA-OAEP encrypt 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密 envelope 字符串 → 明文。
     *
     * @param envelope {@code v1:<base64>} 格式（encrypt 的输出）
     * @return 明文
     * @throws IllegalArgumentException envelope 格式不对 / version 不支持 / base64 解码失败
     * @throws IllegalStateException 解密失败（密文被篡改 / key 不匹配）
     */
    public String decrypt(String envelope) {
        ensureInitialized();
        if (envelope == null || envelope.isEmpty()) {
            throw new IllegalArgumentException("envelope 不能为空");
        }
        if (!envelope.startsWith(VERSION_PREFIX_V1)) {
            throw new IllegalArgumentException(
                    "envelope version 不支持: 期望前缀 '" + VERSION_PREFIX_V1 + "', 实际 '" +
                    (envelope.length() < 4 ? envelope : envelope.substring(0, 4)) + "' ——" +
                    "可能是旧明文 / 历史数据未迁移,需走硬清空或回填流程");
        }
        byte[] ct;
        try {
            ct = Base64.getDecoder().decode(envelope.substring(VERSION_PREFIX_V1.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("envelope base64 解码失败: " + e.getMessage(), e);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepParams());
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("RSA-OAEP decrypt 失败（密文被篡改或 key 不匹配）: " + e.getMessage(), e);
        }
    }

    // ===== 内部工具 =====

    /**
     * 显式 OAEP 参数：主摘要 SHA-256 + MGF1 也用 SHA-256。
     *
     * <p>不显式传时 JDK 的 {@code OAEPWithSHA-256AndMGF1Padding} 会让 MGF1 退回 SHA-1，
     * 造成主摘要与 MGF 摘要不一致，跟别的实现(openssl / 其他语言)互操作时解不开。
     */
    private static OAEPParameterSpec oaepParams() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    /** 解析 PKCS#8 PEM → PrivateKey。 */
    private static PrivateKey parsePkcs8Pem(String pem) {
        String normalized = pem == null ? "" : pem.trim();
        if (normalized.contains("-----BEGIN OPENSSH PRIVATE KEY-----")) {
            throw new IllegalStateException(
                    "私钥格式错误: 检测到 OpenSSH 私钥(-----BEGIN OPENSSH PRIVATE KEY-----)，" +
                    "JDK PKCS8EncodedKeySpec 不能解析。请重新生成 PKCS#8 私钥: " +
                    "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out /etc/imawx/totp.key");
        }
        if (normalized.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            throw new IllegalStateException(
                    "私钥格式错误: 检测到 PKCS#1 RSA 私钥(-----BEGIN RSA PRIVATE KEY-----)，" +
                    "请转换为 PKCS#8: openssl pkcs8 -topk8 -nocrypt -in old.key -out /etc/imawx/totp.key");
        }
        String body = pem
                .replace(PEM_HEADER, "")
                .replace(PEM_FOOTER, "")
                .replaceAll("\\s", "");
        if (body.isEmpty()) {
            throw new IllegalStateException(
                    "PEM 内容为空或缺少 " + PEM_HEADER + " 头 —— 确认是 PKCS#8 私钥(openssl genpkey)，" +
                    "不是 PKCS#1(-----BEGIN RSA PRIVATE KEY-----) 或 OpenSSH 私钥");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PEM base64 解码失败: " + e.getMessage(), e);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "PKCS#8 私钥解析失败: " + e.getMessage() +
                    " —— 确认格式是 PKCS#8(openssl genpkey)，PKCS#1/OpenSSH 需先转换", e);
        }
    }

    /** 从 CRT 私钥推导公钥(modulus + publicExponent)。 */
    private static PublicKey derivePublicKey(PrivateKey priv) {
        if (!(priv instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException(
                    "RSA 私钥缺少 CRT 参数(publicExponent)，无法推导公钥 —— " +
                    "openssl 生成的标准 RSA 私钥带 CRT，检查 key 是否被裁剪");
        }
        try {
            RSAPublicKeySpec spec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("从私钥推导公钥失败: " + e.getMessage(), e);
        }
    }

    private void ensureInitialized() {
        if (privateKey == null || publicKey == null) {
            throw new IllegalStateException(
                    "RsaOaepCipher 未初始化 —— 检查 SecurityConfig.initCipher() 是否被 Spring 执行");
        }
    }
}
