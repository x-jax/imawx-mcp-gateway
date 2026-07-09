package com.imawx.mcp.gateway.bootstrap;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.imawx.mcp.gateway.common.config.AuthProperties;
import com.imawx.mcp.gateway.common.security.RsaOaepCipher;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.entity.do_.McpSystemConfigDO;
import com.imawx.mcp.gateway.entity.do_.McpUserDO;
import com.imawx.mcp.gateway.mapper.McpSystemConfigMapper;
import com.imawx.mcp.gateway.mapper.McpUserMapper;
import com.imawx.mcp.gateway.service.auth.McpUserService;
import com.imawx.mcp.gateway.service.auth.TotpService;
import com.imawx.mcp.gateway.service.system.McpSystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * 生产首启管理员 bootstrap。
 *
 * <p>DDL 只能建表和默认配置，TOTP secret 必须用运行时 RSA 私钥加密，不能在 SQL 里静态 seed。
 * 所以这里在 DDL 完成后补齐 {@code id=1} 的系统管理员，并把一次性登录信息写到本机受控文件。
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class AdminBootstrapper {

    private static final long ADMIN_ID = 1L;
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_EMAIL = "admin@imawx.local";
    private static final String ADMIN_DISPLAY_NAME = "System Administrator";
    private static final String CONFIG_TOTP_ENABLED = "mcp.auth.totp-enabled";
    private static final String ISSUER = "imawx-mcp-gateway";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final McpUserMapper userMapper;
    private final McpSystemConfigMapper configMapper;
    private final McpSystemConfigService configService;
    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final RsaOaepCipher cipher;
    private final McpGatewayProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    @Transactional
    public void bootstrapAdmin() {
        log.info("[bootstrap-admin] checking production admin bootstrap, expected log dir={}",
                Path.of(properties.getLog().getDir()).toAbsolutePath().normalize());
        McpUserDO admin = userMapper.selectById(ADMIN_ID);
        boolean created = false;
        boolean issuedTotp = false;
        String initialPassword = null;
        String totpSecret = null;
        String otpauthUri = null;
        String loginEmail = ADMIN_EMAIL;

        if (admin == null) {
            log.warn("[bootstrap-admin] admin user not found, creating initial admin account: id={}, email={}",
                    ADMIN_ID, ADMIN_EMAIL);
            initialPassword = randomToken(24);
            totpSecret = totpService.generateSecret();
            otpauthUri = totpService.buildOtpauthUri(ISSUER, loginEmail, totpSecret);

            admin = new McpUserDO();
            admin.setId(ADMIN_ID);
            admin.setUsername(ADMIN_USERNAME);
            admin.setEmail(ADMIN_EMAIL);
            admin.setDisplayName(ADMIN_DISPLAY_NAME);
            admin.setPasswordHash(passwordEncoder.encode(initialPassword));
            admin.setStatus(1);
            admin.setMustChangePassword(1);
            admin.setTotpSecret(cipher.encrypt(totpSecret));
            admin.setTotpVerifiedAt(null);
            admin.setCreateTime(LocalDateTime.now());
            admin.setUpdateTime(LocalDateTime.now());
            userMapper.insert(admin);
            created = true;
            issuedTotp = true;
        } else if (admin.getTotpSecret() == null || admin.getTotpSecret().isBlank()) {
            loginEmail = admin.getEmail() == null || admin.getEmail().isBlank() ? ADMIN_EMAIL : admin.getEmail();
            log.warn("[bootstrap-admin] admin exists but TOTP secret is empty, issuing new TOTP material: id={}, email={}",
                    ADMIN_ID, loginEmail);
            totpSecret = totpService.generateSecret();
            otpauthUri = totpService.buildOtpauthUri(ISSUER, loginEmail, totpSecret);

            McpUserDO update = new McpUserDO();
            update.setTotpSecret(cipher.encrypt(totpSecret));
            update.setTotpVerifiedAt(null);
            userMapper.update(update, new LambdaUpdateWrapper<McpUserDO>().eq(McpUserDO::getId, ADMIN_ID));
            issuedTotp = true;
        }

        ensureTotpEnabled();
        configService.reloadCache();
        authProperties.refresh();

        if (created || issuedTotp) {
            Path bootstrapFile = writeBootstrapFile(loginEmail, created, initialPassword, issuedTotp, totpSecret, otpauthUri);
            log.warn("[bootstrap-admin] bootstrap file created: path={}, createdAdmin={}, issuedTotp={}. " +
                            "Read it once, store securely, then delete it.",
                    bootstrapFile.toAbsolutePath(), created, issuedTotp);
        } else {
            log.info("[bootstrap-admin] admin already initialized, no bootstrap file generated: id={}, email={}",
                    ADMIN_ID, admin.getEmail());
        }
    }

    private void ensureTotpEnabled() {
        McpSystemConfigDO row = new McpSystemConfigDO();
        row.setConfigKey(CONFIG_TOTP_ENABLED);
        row.setConfigValue("1");
        row.setDescription("TOTP 2FA 总开关，生产默认启用");
        row.setUpdatedBy("bootstrap");
        row.setUpdateTime(LocalDateTime.now());
        McpSystemConfigDO existing = configMapper.selectById(CONFIG_TOTP_ENABLED);
        if (existing == null) {
            row.setCreateTime(LocalDateTime.now());
            configMapper.insert(row);
        } else if (!"1".equals(existing.getConfigValue())) {
            configMapper.updateById(row);
        }
    }

    private Path writeBootstrapFile(String loginEmail,
                                    boolean created,
                                    String initialPassword,
                                    boolean issuedTotp,
                                    String totpSecret,
                                    String otpauthUri) {
        try {
            Path dir = Path.of(properties.getLog().getDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve("bootstrap-admin-" + TS.format(LocalDateTime.now()) + ".txt");
            log.warn("[bootstrap-admin] writing bootstrap file: dir={}, path={}", dir, file.toAbsolutePath());
            StringBuilder body = new StringBuilder();
            body.append("imawx-mcp-gateway admin bootstrap\n");
            body.append("generated_at=").append(LocalDateTime.now()).append('\n');
            body.append("login_email=").append(loginEmail).append('\n');
            if (created) {
                body.append("initial_password=").append(initialPassword).append('\n');
            } else {
                body.append("initial_password=<unchanged>\n");
            }
            if (issuedTotp) {
                body.append("totp_secret_base32=").append(totpSecret).append('\n');
                body.append("totp_otpauth_uri=").append(otpauthUri).append('\n');
                body.append("totp_current_code=").append(totpService.currentCode(totpSecret)).append('\n');
                body.append("totp_current_code_valid_until_epoch=")
                        .append(totpService.currentCodeValidUntilEpochSeconds()).append('\n');
            }
            body.append("\nThis file contains production bootstrap credentials. Store them securely and delete this file after first login.\n");
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
            restrictOwnerOnly(file);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("failed to write admin bootstrap file under log dir: " + e.getMessage(), e);
        }
    }

    private static void restrictOwnerOnly(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem. The file is still created under the configured application log directory.
        }
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        SECURE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
