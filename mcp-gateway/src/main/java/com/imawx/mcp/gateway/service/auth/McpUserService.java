package com.imawx.mcp.gateway.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.imawx.mcp.gateway.common.config.AuthProperties;
import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.security.RsaOaepCipher;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.entity.do_.McpUserDO;
import com.imawx.mcp.gateway.entity.dto.McpUserCreateDTO;
import com.imawx.mcp.gateway.entity.dto.McpUserQueryDTO;
import com.imawx.mcp.gateway.entity.dto.McpUserUpdateDTO;
import com.imawx.mcp.gateway.entity.vo.McpUserCreatedVO;
import com.imawx.mcp.gateway.entity.vo.McpUserInfoVO;
import com.imawx.mcp.gateway.entity.vo.McpUserListVO;
import com.imawx.mcp.gateway.entity.vo.McpTotpSetupVO;
import com.imawx.mcp.gateway.mapper.McpUserMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 账号服务 —— 登录鉴权 + 用户管理(2026-07-03 扩展,2026-07-04 重构 TOTP 流程)。
 *
 * <p>登录流程(2026-07-04 重构):
 * <ol>
 *   <li>解析 account → 拿 user(account 必须含 @,2026-07-03 起禁用 username 登录)</li>
 *   <li><b>2026-07-04 改:全局 TOTP 总开关</b>(来自配置表 {@code mcp.auth.totp-enabled})
 *       <ul>
 *         <li>开关关闭 → 跳过所有 TOTP 校验(dev profile 默认)</li>
 *         <li>开关开启 + 用户 secret 缺失 → 40103 "请联系管理员初始化两步验证"
 *             (admin 自己创建 admin 时自动生成密钥;老数据 NULL 让 admin 在 /system/user 点"重置密钥"补)</li>
 *         <li>开关开启 + 用户有 secret(verified_at 可能为 null) → 校验 6 位 code;首次 verify 通过置 verified_at</li>
 *       </ul>
 *   </li>
 *   <li>校验 password(BCrypt)</li>
 *   <li>user.status==1(2026-07-03 保留 —— 之前就有,现在 token 鉴权也加这个)</li>
 *   <li>通过 → 写 session + 返 userInfo</li>
 * </ol>
 *
 * <p><b>TOTP 协议本身要求 per-user 密钥</b>(不能"系统一个密钥")。所以虽然
 * "配置表管 TOTP 总开关",但密钥仍然在 user 表。当前设计闭环:
 * <ul>
 *   <li>总开关 → {@code mcp_system_config.mcp.auth.totp-enabled}(配置表管,管理员在控制台配)</li>
 *   <li>密钥分配 → create() 时自动生成 + 一次性返明文给 admin(不允许 admin/用户"手动配发")</li>
 *   <li>密钥重置 → admin 在 /system/user 用户列表点"重置密钥"按钮,用户丢 App 时用</li>
 *   <li>个人不能关闭 / 重置 TOTP(TOTP 是系统级强制,不是用户级偏好)</li>
 * </ul>
 *
 * <p>管理流程(2026-07-03 加):
 * <ul>
 *   <li>{@link #ADMIN_USER_ID} = 1L 写死,所有 admin 操作都要求 currentUserId==1</li>
 *   <li>admin 不能被禁用、不能被删(虽然本来就不删)、不能"降级"为普通(写死后无 is_admin 字段)</li>
 *   <li>非 admin 用户只能改自己的 profile / password</li>
 *   <li>账号不允许删除 —— Service 故意不暴露 delete(2026-07-03 加需求)</li>
 * </ul>
 *
 * <p>个人中心流程(2026-07-04 砍) —— 个人中心不再有 TOTP 任何操作;
 * 仅保留改 displayName / email / password。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpUserService {

    /**
     * 系统管理员 userId(2026-07-03 写死)—— {@code mcp_user.id = 1} 是唯一管理员。
     *
     * <p>项目不是 SaaS、没 RBAC,需求方决定写死:任何"管理员才能做"的判定都基于
     * {@code currentUserId == 1}。不增 is_admin 列,DDL 漂移最小。
     */
    public static final Long ADMIN_USER_ID = 1L;

    private final McpUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    /**
     * RSA-OAEP cipher(2026-07-04 重构)—— DB 里的 totp_secret 改存密文 envelope,
     * 所有 setTotpSecret 前要 encrypt、所有 getTotpSecret 后要 decrypt。
     * cipher 由 SecurityConfig 启动时 init(PKCS#8 PEM 私钥)。
     */
    private final RsaOaepCipher cipher;
    /**
     * 认证配置 —— {@code mcp-gateway.auth.totp-enabled} 总开关。
     * dev 默认 false(跳过 TOTP 校验),prod 默认 true(严格 2FA)。
     */
    private final AuthProperties authProperties;

    // ============================================================
    // 登录 / 鉴权
    // ============================================================

    /**
     * 单步登录 —— 校验 account + (TOTP) + password。
     *
     * <p>禁用 username 登录,account 必须含 {@code @}(email 格式)。
     *
     * @param account     邮箱(必须含 @)—— 无 @ 直接返 BAD_CREDENTIALS
     * @param rawPassword 明文密码
     * @param totpCode    6 位 TOTP code 或 8 个 backup code;启用了 2FA 必填,未启用忽略
     * @return 登录成功后的账号信息(不含 passwordHash)
     */
    public McpUserInfoVO login(String account, String rawPassword, String totpCode) {
        if (account == null || !account.contains("@")) {
            throw new BizException(BizErrorCode.BAD_CREDENTIALS, "请使用邮箱登录(账号必须包含 @)");
        }
        McpUserDO user = resolveUser(account);

        // 2026-07-04 改:TOTP 总开关关闭 → 跳过所有 TOTP 校验(原逻辑)
        // 2026-07-04 改:TOTP 总开关开启 + 用户 secret 缺失 → 40103 "请联系管理员初始化两步验证"
        // 2026-07-04 改:TOTP 总开关开启 + 用户已配 secret(verified_at 可能为 null) → 校验 TOTP
        //   - 首次 verify(verified_at == null) 通过后置 verified_at
        //   - 已 verify(verified_at != null) 正常校验
        boolean totpRequired = authProperties.isTotpEnabled();
        boolean hasSecret = user.getTotpSecret() != null && !user.getTotpSecret().isBlank();

        if (totpRequired && !hasSecret) {
            // 2026-07-04 改:全局 TOTP 开启 + 用户没配 secret → 拒绝登录,让用户找 admin
            // (admin 自身 secret 在系统启动时由 BootstrapRunner 自动生成,日志里打印)
            log.warn("[auth-login-fail] reason=totp_not_initialized userId={} account={}", user.getId(), account);
            throw new BizException(BizErrorCode.TOTP_NOT_INITIALIZED,
                    "请联系管理员初始化两步验证,或查看启动日志获取 admin bootstrap secret");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.warn("[auth-login-fail] reason=bad_password userId={} account={}", user.getId(), account);
            throw new BizException(BizErrorCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            log.warn("[auth-login-fail] reason=account_disabled userId={} account={}", user.getId(), account);
            throw new BizException(BizErrorCode.ACCOUNT_DISABLED);
        }

        if (totpRequired && hasSecret) {
            if (totpCode == null || totpCode.isBlank()) {
                log.warn("[auth-login-fail] reason=totp_missing userId={} account={}", user.getId(), account);
                throw new BizException(BizErrorCode.BAD_CREDENTIALS, "请输入 6 位 TOTP 验证码");
            }
            // 2026-07-04 改:user.totpSecret 现在是 v1:<base64> 密文,verify 前先 decrypt
            // 回明文 base32,才能用 TotpService.verify 算码
            String plainSecret = cipher.decrypt(user.getTotpSecret());
            if (!totpService.verify(plainSecret, totpCode)) {
                log.warn("[auth-login-fail] reason=bad_totp userId={} account={} codeLength={}",
                        user.getId(), account, totpCode.replaceAll("[\\s-]", "").length());
                throw new BizException(BizErrorCode.BAD_CREDENTIALS, "TOTP 验证码错误");
            }
            // 2026-07-04 改:首次 verify 通过 → 置 verified_at(用户已配 App 但没正式 verify)
            if (user.getTotpVerifiedAt() == null) {
                McpUserDO u = new McpUserDO();
                u.setId(user.getId());
                u.setTotpVerifiedAt(LocalDateTime.now());
                userMapper.updateById(u);
                log.info("[auth-totp-first-verify] userId={} verified_at set", user.getId());
            }
        }

        // 通过 → 更新 lastLoginAt
        McpUserDO u = new McpUserDO();
        u.setLastLoginAt(LocalDateTime.now());
        userMapper.update(u, new LambdaUpdateWrapper<McpUserDO>().eq(McpUserDO::getId, user.getId()));
        log.info("[auth-login] userId={} username={} totpRequired={} hasSecret={}",
                user.getId(), user.getUsername(), totpRequired, hasSecret);
        return toVO(user);
    }

    public McpUserInfoVO findById(Long id) {
        McpUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(BizErrorCode.NOT_FOUND, "账号不存在");
        }
        return toVO(user);
    }

    public McpUserInfoVO writeSessionAndVO(HttpSession session, McpUserInfoVO vo) {
        session.setAttribute(SessionKeys.USER_ID, Long.parseLong(vo.getId()));
        session.setAttribute(SessionKeys.USERNAME, vo.getUsername());
        session.setAttribute(SessionKeys.EMAIL, vo.getEmail());
        session.setAttribute(SessionKeys.MUST_CHANGE_PASSWORD, Boolean.TRUE.equals(vo.getMustChangePassword()));
        return vo;
    }

    public McpUserDO resolveUser(String account) {
        McpUserDO user = null;
        if (account != null && account.contains("@")) {
            user = userMapper.selectByEmail(account);
        } else {
            user = userMapper.selectByUsername(account);
        }
        if (user == null) {
            throw new BizException(BizErrorCode.BAD_CREDENTIALS);
        }
        return user;
    }

    // ============================================================
    // 权限工具(2026-07-03 加)
    // ============================================================

    /**
     * 是否系统管理员 —— 写死 {@code userId == 1L}。
     */
    public static boolean isAdmin(Long userId) {
        return userId != null && ADMIN_USER_ID.equals(userId);
    }

    /**
     * 管理员操作前置检查 —— 要求 currentUserId 是 admin,否则 403。
     */
    public static void requireAdmin(Long currentUserId) {
        if (!isAdmin(currentUserId)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }

    /**
     * 修改其他用户的权限检查 —— admin 可改任何人,非 admin 只能改自己。
     * 改自己时调用方直接走 {@link #updateOwnProfile} 路径,不走这个。
     */
    public static void requireAdminOrSelf(Long currentUserId, Long targetUserId) {
        if (currentUserId == null || targetUserId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED);
        }
        if (isAdmin(currentUserId)) return;
        if (currentUserId.equals(targetUserId)) return;
        throw new BizException(BizErrorCode.FORBIDDEN, "非管理员只能修改自己");
    }

    // ============================================================
    // 用户管理 —— admin 路径(2026-07-03 加)
    // ============================================================

    /**
     * 用户列表(分页 + 搜索)—— admin only。
     *
     * <p>2026-07-03 实现注意:不用 MyBatis Plus 的 selectPage —— 它在 wrapper 含
     * {@code .and(zw -> ...)} 嵌套时,内部 count SQL 生成有 bug 导致 total=0。
     * 改用 selectCount + selectList + 手动 LIMIT,简单且 count 准确。
     */
    public PageResult<McpUserListVO> page(Long currentUserId, McpUserQueryDTO q) {
        requireAdmin(currentUserId);
        LambdaQueryWrapper<McpUserDO> w = new LambdaQueryWrapper<McpUserDO>()
                .orderByDesc(McpUserDO::getCreateTime);
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String kw = q.getKeyword().trim();
            // OR 三个字段 like —— 不嵌套 .and(),count 才能正确算
            w.and(zw -> zw.like(McpUserDO::getUsername, kw)
                    .or().like(McpUserDO::getEmail, kw)
                    .or().like(McpUserDO::getDisplayName, kw));
        }
        if (q.getStatus() != null) {
            w.eq(McpUserDO::getStatus, q.getStatus());
        }
        long total = userMapper.selectCount(w);
        int pageNum = q.getPageNum() == null || q.getPageNum() < 1 ? 1 : q.getPageNum();
        int pageSize = q.getPageSize() == null || q.getPageSize() < 1 ? 20 : q.getPageSize();
        int offset = (pageNum - 1) * pageSize;
        w.last("LIMIT " + offset + "," + pageSize);
        List<McpUserDO> rows = userMapper.selectList(w);
        List<McpUserListVO> records = rows.stream().map(this::toListVO).toList();
        return new PageResult<>(records, total, pageNum, pageSize);
    }

    /**
     * 创建用户 —— admin only。
     *
     * <p>username / email / displayName / 初始密码 全部由 caller 显式提供(2026-07-03 改):
     * 原来 username 走"email 本地部分 + 4 位随机后缀"的自动生成规则,管理员没法选稳定可读的
     * 标识;改成显式传入 + 唯一性校验,语义更清晰。
     *
     * <p><b>2026-07-04 改:TOTP secret 在创建时自动生成</b>(不允许 "manual 配发")。
     * TOTP 协议本身就是 per-user secret,create 时一次性生成 base32 明文 → DB 存
     * RSA-OAEP 密文 envelope → 响应返明文 + otpauth URI 给 admin,admin 转给用户扫码。
     * user.totp_verified_at = null(用户首次 login verify 通过后再写入)。
     *
     * @return {@link McpUserCreatedVO} —— 含 id / username / displayName / email / status
     *         + 一次性明文 totpSecret + otpauthUri(admin 拿到后转给用户)
     */
    @Transactional
    public McpUserCreatedVO create(Long currentUserId, McpUserCreateDTO dto) {
        requireAdmin(currentUserId);
        validateCreate(dto);
        // username 唯一(DTO 里 @Pattern 已经做格式校验,这里只查冲突)
        if (userMapper.selectByUsername(dto.getUsername()) != null) {
            throw new BizException(BizErrorCode.CONFLICT, "username 已存在: " + dto.getUsername());
        }
        // email 唯一
        if (userMapper.selectByEmail(dto.getEmail()) != null) {
            throw new BizException(BizErrorCode.CONFLICT, "email 已存在: " + dto.getEmail());
        }

        // 2026-07-04 改:create() 自动生成 TOTP secret(不允许后续手动配发)
        // TOTP 协议要求 per-user,创建时一次性分配,本次响应带明文给 admin 转给用户
        String secret = totpService.generateSecret();
        String otpauthUri = totpService.buildOtpauthUri(
                "imawx-mcp-gateway",
                dto.getEmail() != null ? dto.getEmail() : dto.getUsername(),
                secret);

        McpUserDO u = new McpUserDO();
        u.setUsername(dto.getUsername());
        u.setPasswordHash(passwordEncoder.encode(dto.getInitialPassword()));
        u.setDisplayName(dto.getDisplayName());
        u.setEmail(dto.getEmail());
        u.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        u.setMustChangePassword(1);
        // secret 写密文 envelope,verified_at 不写(等用户首次 login verify)
        u.setTotpSecret(cipher.encrypt(secret));
        u.setTotpVerifiedAt(null);
        userMapper.insert(u);

        log.info("[user-create] by={} newUserId={} username={} email={} totpSecretGenerated=true",
                currentUserId, u.getId(), u.getUsername(), u.getEmail());
        return McpUserCreatedVO.builder()
                .id(String.valueOf(u.getId()))
                .username(u.getUsername())
                .displayName(u.getDisplayName())
                .email(u.getEmail())
                .status(u.getStatus())
                .totpSecret(secret)
                .totpOtpauthUri(otpauthUri)
                .build();
    }

    /**
     * 编辑用户(displayName / email / status)—— admin 可改任何人;非 admin 只能改自己(走 {@link #updateOwnProfile})。
     *
     * <p>2026-07-03 加保护:
     * <ul>
     *   <li>admin(id=1) 不能被改 status=0(禁 admin)</li>
     *   <li>email 唯一</li>
     * </ul>
     */
    @Transactional
    public void update(Long currentUserId, Long targetId, McpUserUpdateDTO dto) {
        requireAdminOrSelf(currentUserId, targetId);
        McpUserDO exist = userMapper.selectById(targetId);
        if (exist == null) {
            throw new BizException(BizErrorCode.NOT_FOUND, "账号不存在");
        }
        // admin 不能被禁用
        if (isAdmin(targetId) && dto.getStatus() != null && dto.getStatus() == 0) {
            throw new BizException(BizErrorCode.FORBIDDEN, "管理员账号不允许被禁用");
        }
        if (dto.getEmail() != null && !dto.getEmail().equals(exist.getEmail())) {
            if (userMapper.selectByEmail(dto.getEmail()) != null) {
                throw new BizException(BizErrorCode.CONFLICT, "email 已被其他账号使用");
            }
        }
        McpUserDO u = new McpUserDO();
        u.setId(targetId);
        if (dto.getDisplayName() != null) u.setDisplayName(dto.getDisplayName());
        if (dto.getEmail() != null) u.setEmail(dto.getEmail());
        if (dto.getStatus() != null) u.setStatus(dto.getStatus());
        userMapper.updateById(u);
        log.info("[user-update] by={} targetId={} fields={}", currentUserId, targetId,
                (dto.getDisplayName() != null ? "displayName " : "")
                        + (dto.getEmail() != null ? "email " : "")
                        + (dto.getStatus() != null ? "status " : ""));
    }

    /**
     * 重置密码 —— admin only(admin 可重置任何人的密码;非 admin 想改密码走 {@link #updateOwnPassword})。
     */
    @Transactional
    public void resetPassword(Long currentUserId, Long targetId, String newPassword) {
        requireAdmin(currentUserId);
        validatePassword(newPassword);
        McpUserDO exist = userMapper.selectById(targetId);
        if (exist == null) {
            throw new BizException(BizErrorCode.NOT_FOUND, "账号不存在");
        }
        McpUserDO u = new McpUserDO();
        u.setId(targetId);
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        u.setMustChangePassword(1);
        userMapper.updateById(u);
        log.info("[user-reset-password] by={} targetId={}", currentUserId, targetId);
    }

    // ============================================================
    // 个人中心 —— 任何登录用户(2026-07-03 加)
    // ============================================================

    @Transactional
    public void updateOwnProfile(Long currentUserId, String displayName, String email) {
        if (currentUserId == null) throw new BizException(BizErrorCode.UNAUTHORIZED);
        if (email != null) {
            McpUserDO other = userMapper.selectByEmail(email);
            if (other != null && !other.getId().equals(currentUserId)) {
                throw new BizException(BizErrorCode.CONFLICT, "email 已被其他账号使用");
            }
        }
        McpUserDO u = new McpUserDO();
        u.setId(currentUserId);
        if (displayName != null) u.setDisplayName(displayName);
        if (email != null) u.setEmail(email);
        userMapper.updateById(u);
        log.info("[user-update-own-profile] userId={}", currentUserId);
    }

    @Transactional
    public void updateOwnPassword(Long currentUserId, String oldPassword, String newPassword) {
        if (currentUserId == null) throw new BizException(BizErrorCode.UNAUTHORIZED);
        validatePassword(newPassword);
        McpUserDO exist = userMapper.selectById(currentUserId);
        if (exist == null) throw new BizException(BizErrorCode.NOT_FOUND, "账号不存在");
        if (!passwordEncoder.matches(oldPassword, exist.getPasswordHash())) {
            throw new BizException(BizErrorCode.BAD_CREDENTIALS, "旧密码错误");
        }
        McpUserDO u = new McpUserDO();
        u.setId(currentUserId);
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        u.setMustChangePassword(0);
        userMapper.updateById(u);
        log.info("[user-update-own-password] userId={}", currentUserId);
    }

    // ============================================================
    // TOTP 管理(2026-07-03 加)—— admin 替用户 + 用户自己
    // ============================================================

    /**
     * 重置 TOTP 密钥(2026-07-04 把 setupTotp 改名 + 改语义)。
     *
     * <p>应用场景:<b>用户丢失 Authenticator App 后,运维恢复路径</b>。
     *
     * <p>行为:覆盖旧 secret(重新 160-bit 随机 base32) + 清 verified_at。
     * 用户下次 login 必须重新 verify,输对 6 位码后被写入 verified_at(等同"重新启用 2FA")。
     *
     * <p>设计原则:
     * <ul>
     *   <li><b>不允许</b>"create user → admin 手动配发密钥"工作流(create 已自动生成)</li>
     *   <li>这条路径只为"丢 App" 的运维兜底,不是默认初始化流程</li>
     *   <li>不允许 admin 重置自己的密钥 —— admin lock out 后只能由部署 / 启动日志恢复
     *       (参见 {@code TotpBootstrapRunner},虽然本次改也删除掉了)</li>
     * </ul>
     */
    @Transactional
    public McpTotpSetupVO resetTotp(Long currentUserId, Long targetId) {
        requireAdmin(currentUserId);
        McpUserDO exist = userMapper.selectById(targetId);
        if (exist == null) throw new BizException(BizErrorCode.NOT_FOUND, "账号不存在");
        if (Objects.equals(targetId, ADMIN_USER_ID)) {
            // admin 自重置会把当前操作人锁出去 → 禁止
            throw new BizException(BizErrorCode.FORBIDDEN, "管理员账号不允许通过 UI 重置密钥");
        }
        String secret = totpService.generateSecret();
        String issuer = "imawx-mcp-gateway";
        String label = exist.getEmail() != null ? exist.getEmail() : exist.getUsername();
        String otpauthUri = totpService.buildOtpauthUri(issuer, label, secret);

        // 覆盖旧 secret + 清 verified_at;前端拿到的是明文 base32(用户要扫 QR)
        McpUserDO u = new McpUserDO();
        u.setId(targetId);
        u.setTotpSecret(cipher.encrypt(secret));
        u.setTotpVerifiedAt(null);
        userMapper.updateById(u);

        log.warn("[totp-reset] actor={} target={} username={} new-secret-issued",
                currentUserId, targetId, exist.getUsername());
        return McpTotpSetupVO.builder()
                .secret(secret)
                .otpauthUri(otpauthUri)
                .build();
    }

    /**
     * 查 TOTP 状态(简化版,2026-07-04)—— 仅返回"是否已启用"。
     *
     * <p>enabled = {@code totp_verified_at != null}(用户首次 verify 过 2FA 才算启用)。
     * 原有 backupCount 等字段去掉(列已 DROP)。
     */
    public TotpStatusVO getTotpStatus(Long currentUserId, Long targetId) {
        requireAdmin(currentUserId);
        McpUserDO exist = userMapper.selectById(targetId);
        if (exist == null) throw new BizException(BizErrorCode.NOT_FOUND, "账号不存在");
        return new TotpStatusVO(exist.getTotpVerifiedAt() != null, exist.getTotpVerifiedAt());
    }

    // ============================================================
    // 校验 / VO 转换
    // ============================================================

    /**
     * 创建入参的 service 层兜底校验。
     * <p>字段格式(username / email / displayName / password 长度)已由 DTO 上的
     * {@code @NotBlank / @Email / @Pattern / @Size} 在 Controller {@code @Valid} 阶段拦截;
     * 这里只做"避免 NPE / 二次确认密码长度"的轻量兜底,真正格式校验不要在这里重复加,
     * 否则规则散落两处容易漂移。
     */
    private void validateCreate(McpUserCreateDTO dto) {
        if (dto == null) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "请求体不能为空");
        }
        validatePassword(dto.getInitialPassword());
    }

    private void validatePassword(String raw) {
        if (raw == null || raw.length() < 8) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "密码长度至少 8 位");
        }
        if (raw.length() > 64) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "密码长度不能超过 64 位");
        }
    }

    private McpUserInfoVO toVO(McpUserDO u) {
        McpUserInfoVO vo = new McpUserInfoVO();
        vo.setId(String.valueOf(u.getId()));
        vo.setUsername(u.getUsername());
        vo.setDisplayName(u.getDisplayName());
        vo.setEmail(u.getEmail());
        vo.setStatus(u.getStatus());
        vo.setMustChangePassword(Integer.valueOf(1).equals(u.getMustChangePassword()));
        vo.setLastLoginAt(u.getLastLoginAt());
        vo.setCreatedAt(u.getCreateTime());
        // 2026-07-03 改:角色按 userId 写死 —— admin(id=1)=R_SUPER,其他=R_ADMIN
        vo.setRoles(isAdmin(u.getId()) ? List.of("R_SUPER") : List.of("R_ADMIN"));
        vo.setButtons(List.of());
        return vo;
    }

    private McpUserListVO toListVO(McpUserDO u) {
        return McpUserListVO.builder()
                .id(String.valueOf(u.getId()))
                .username(u.getUsername())
                .displayName(u.getDisplayName())
                .email(u.getEmail())
                .status(u.getStatus())
                .isAdmin(isAdmin(u.getId()))
                // 2026-07-04 改:totp_enabled 列已删,改用 verified_at != null 派生
                .totpEnabled(u.getTotpVerifiedAt() != null)
                .lastLoginAt(u.getLastLoginAt())
                .createTime(u.getCreateTime())
                .updateTime(u.getUpdateTime())
                .build();
    }

    public record PageResult<T>(List<T> records, long total, int pageNum, int pageSize) {
    }

    /** TOTP 状态 VO(2026-07-04 简化)—— 仅含启用标志 + verified_at,不再含 backupCount(列已 DROP)。 */
    public record TotpStatusVO(boolean enabled, LocalDateTime verifiedAt) {
    }
}
