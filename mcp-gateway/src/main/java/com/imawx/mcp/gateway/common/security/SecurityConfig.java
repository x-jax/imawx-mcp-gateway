package com.imawx.mcp.gateway.common.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;

/**
 * RSA-OAEP cipher bootstrap（2026-07-04 重构：AES 对称 key → RSA PEM 私钥）——
 * 启动时按优先级解析 PKCS#8 PEM 私钥，喂给 {@link RsaOaepCipher}，
 * 做 prod profile 的 dev-key 拒绝校验。
 *
 * <p><b>key 加载优先级</b>（命中后即停止）：
 * <ol>
 *   <li>环境变量 {@code MCP_GATEWAY_SECURITY_TOTP_KEY} —— 直接 PKCS#8 PEM 文本
 *       （含头尾行与换行），任何 profile 都生效。PEM 多行放 env var 稍别扭，
 *       优先用下面的 _FILE 方式</li>
 *   <li>环境变量 {@code MCP_GATEWAY_SECURITY_TOTP_KEY_FILE} —— 指向 PEM 私钥文件路径，
 *       任何 profile 都生效（K8s secret mount 首选）</li>
 *   <li>dev profile 专属 fallback：用户家目录 {@code ~/.imawx-mcp-gateway/totp.key}，
 *       文件不存在就在 JVM 内生成 RSA-4096 PKCS#8 PEM 写入（权限 0600）。
 *       产物与 {@code openssl genpkey} 的 PKCS#8 PEM 同格式，免得 dev 还得装 openssl</li>
 * </ol>
 *
 * <p><b>prod 手动生成 key</b>（推荐 openssl genpkey，产物为 PKCS#8 {@code BEGIN PRIVATE KEY}）：
 * <pre>{@code
 *   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out /etc/imawx/totp.key
 *   chmod 600 /etc/imawx/totp.key
 *   export MCP_GATEWAY_SECURITY_TOTP_KEY_FILE=/etc/imawx/totp.key
 * }</pre>
 *
 * <p><b>prod profile 硬约束</b>：
 * <ul>
 *   <li>必须命中 1 或 2，不允许用 dev 自动生成（否则 prod 加密数据用 dev key，
 *       后期切到 prod key 全部解不开）</li>
 *   <li>key 来源是 dev 自动生成路径 → 启动拒绝</li>
 * </ul>
 *
 * <p><b>设计动机</b>：密钥彻底脱离源码——dev 本机生成落文件(gitignore 排除)，
 * prod 走 env / secret 注入。PEM + openssl 便于密钥管理系统托管与轮换。
 */
@Slf4j
@Configuration("mcpCipherBootstrap")
@RequiredArgsConstructor
public class SecurityConfig {

    /** dev profile 自动生成 key 的路径：用户家目录 + 子目录 + 文件名。 */
    private static final Path DEV_KEY_PATH =
            Paths.get(System.getProperty("user.home"), ".imawx-mcp-gateway", "totp.key");

    /** dev 自动生成的 RSA 模数长度（bit），跟 prod openssl genpkey 4096 bit 对齐。 */
    private static final int RSA_KEY_BITS = 4096;

    /** 环境变量名（直接 PKCS#8 PEM 文本）。 */
    private static final String ENV_KEY = "MCP_GATEWAY_SECURITY_TOTP_KEY";

    /** 环境变量名（指向 PEM 私钥文件路径）。 */
    private static final String ENV_KEY_FILE = "MCP_GATEWAY_SECURITY_TOTP_KEY_FILE";

    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

    private final RsaOaepCipher cipher;

    @PostConstruct
    public void initCipher() {
        boolean isProd = isProdProfile();
        String pem = resolveKey(isProd);
        cipher.init(pem);
        validateProdNotDevKey(isProd);
    }

    /**
     * 按优先级解析 PKCS#8 PEM 私钥文本。
     */
    private String resolveKey(boolean isProd) {
        // 1) 直接 PEM 文本（env var 注入）
        String envKey = System.getenv(ENV_KEY);
        if (envKey != null && !envKey.isBlank()) {
            log.info("[cipher] key source: env var {}", ENV_KEY);
            return envKey;
        }

        // 2) 文件路径（env var 注入）
        String envKeyFile = System.getenv(ENV_KEY_FILE);
        if (envKeyFile != null && !envKeyFile.isBlank()) {
            log.info("[cipher] key source: env var {} → file {}", ENV_KEY_FILE, envKeyFile);
            try {
                return Files.readString(Paths.get(envKeyFile));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "[cipher] 读 key 文件失败: " + envKeyFile + " — " + e.getMessage(), e);
            }
        }

        // 3) dev profile 专属：自动生成到 user home
        if (!isProd) {
            return loadOrGenerateDevKey();
        }

        // prod 走到这里 → 拒绝启动
        throw new IllegalStateException(
                "[cipher] prod profile 启动拒绝: 必须设置 " + ENV_KEY + " 或 " + ENV_KEY_FILE +
                " 环境变量（PKCS#8 PEM 私钥），不能用 dev 自动生成的 key —— 否则 prod 加密数据用 dev key 后期切 prod key 解不开");
    }

    /**
     * dev 自动生成路径 —— 类似生产 openssl genpkey 首次运行：
     * 文件存在就读，不存在就在 JVM 内生成 RSA-4096 → PKCS#8 PEM → 写入（权限 0600）。
     */
    private String loadOrGenerateDevKey() {
        if (Files.exists(DEV_KEY_PATH)) {
            try {
                String existing = Files.readString(DEV_KEY_PATH);
                log.info("[cipher] key source: dev auto-generated file {}", DEV_KEY_PATH);
                return existing;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "[cipher] 读 dev key 文件失败: " + DEV_KEY_PATH + " — " + e.getMessage(), e);
            }
        }

        String pem = generateRsaPkcs8Pem();
        try {
            Files.createDirectories(DEV_KEY_PATH.getParent());
            if (supportsPosix(DEV_KEY_PATH)) {
                Files.createFile(DEV_KEY_PATH, PosixFilePermissions.asFileAttribute(
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
                Files.setPosixFilePermissions(DEV_KEY_PATH, PosixFilePermissions.fromString("rw-------"));
            } else {
                // Windows / 不支持 POSIX：尽力写，不强求权限
                Files.createFile(DEV_KEY_PATH);
            }
            Files.writeString(DEV_KEY_PATH, pem);
            log.warn("[cipher] dev key auto-generated at {} (RSA-{} PKCS#8 PEM). " +
                    "Add to .gitignore! DO NOT commit this file.", DEV_KEY_PATH, RSA_KEY_BITS);
            return pem;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[cipher] 写 dev key 文件失败: " + DEV_KEY_PATH + " — " + e.getMessage(), e);
        }
    }

    /**
     * JVM 内生成 RSA-4096 密钥对，把私钥导出成 PKCS#8 PEM 文本
     * （与 {@code openssl genpkey} 产物同格式，{@code -----BEGIN PRIVATE KEY-----}）。
     */
    private String generateRsaPkcs8Pem() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(RSA_KEY_BITS);
            KeyPair kp = kpg.generateKeyPair();
            // getEncoded() 对 PrivateKey 返回 PKCS#8 DER
            byte[] pkcs8Der = kp.getPrivate().getEncoded();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8Der);
            return PEM_HEADER + "\n" + base64 + "\n" + PEM_FOOTER + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("[cipher] 生成 RSA-" + RSA_KEY_BITS + " 密钥失败: " + e.getMessage(), e);
        }
    }

    /**
     * prod profile 二次校验：若 dev 自动生成路径存在，提示可能误挂载到 prod。
     */
    private void validateProdNotDevKey(boolean isProd) {
        if (!isProd) return;
        if (Files.exists(DEV_KEY_PATH)) {
            log.warn("[cipher] prod 启动: 发现 dev key 文件 {}，可能误挂载到 prod —— 确认 {} / {} env var 指向真正的 prod key",
                    DEV_KEY_PATH, ENV_KEY, ENV_KEY_FILE);
        }
        log.info("[cipher] prod key 来源已确认: {} = {}, {} = {}",
                ENV_KEY, System.getenv(ENV_KEY) != null ? "已设置" : "未设置",
                ENV_KEY_FILE, System.getenv(ENV_KEY_FILE) != null ? "已设置" : "未设置");
    }

    private static boolean isProdProfile() {
        String active = System.getProperty("spring.profiles.active", "");
        if (active.isEmpty() && System.getenv("SPRING_PROFILES_ACTIVE") != null) {
            active = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        return active.contains("prod");
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
