package com.imawx.mcp.gateway.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imawx.mcp.gateway.common.config.SessionKeys;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpApiTokenDO;
import com.imawx.mcp.gateway.entity.do_.McpTokenBackendAuthorizationDO;
import com.imawx.mcp.gateway.entity.do_.McpTokenToolAuthorizationDO;
import com.imawx.mcp.gateway.entity.dto.McpApiTokenAuthorizationsUpdateDTO;
import com.imawx.mcp.gateway.entity.dto.McpApiTokenCreateDTO;
import com.imawx.mcp.gateway.entity.vo.McpApiTokenCreatedVO;
import com.imawx.mcp.gateway.entity.vo.McpApiTokenVO;
import com.imawx.mcp.gateway.entity.do_.McpUserDO;
import com.imawx.mcp.gateway.mapper.McpApiTokenMapper;
import com.imawx.mcp.gateway.mapper.McpTokenBackendAuthorizationMapper;
import com.imawx.mcp.gateway.mapper.McpTokenToolAuthorizationMapper;
import com.imawx.mcp.gateway.mapper.McpUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;

/**
 * API Token Service。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpApiTokenService {

    /**
     * Token 明文格式:{@code imwx_<32 位 base62 随机>}。
     *
     * <p>为什么用 {@code imwx_} 前缀:跟 GitHub PAT ({@code ghp_})、Stripe ({@code sk_})
     * 一致 —— 用户一眼能识别"这是 token 而不是普通字符串",Secret 管理工具
     * (GitHub Token Scanner 等)能识别模式防泄露。
     *
     * <p>为什么是 base62:字符集是 0-9 a-z A-Z,url/header 安全不需要 url-encode,
     * 比 base64 少两个易混字符(+/=),可读性更好。
     */
    public static final String TOKEN_PREFIX = "imwx_";

    private static final int TOKEN_RANDOM_LENGTH = 32;
    private static final int TOKEN_DISPLAY_PREFIX_LENGTH = 16;

    /** Base62 字符集 —— 数字 + 小写字母 + 大写字母。 */
    private static final char[] BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /** SecureRandom 单例 —— 不要用 {@code new Random()},token 生成必须密码学随机。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpApiTokenMapper tokenMapper;
    private final McpTokenBackendAuthorizationMapper tokenBackendAuthMapper;
    private final McpTokenToolAuthorizationMapper tokenToolAuthMapper;
    private final McpUserMapper userMapper;

/**
     * 创建 token —— 明文只返这一次,DB 永远不存明文。
     *
     * <p>2026-07-03 改:支持授权范围 —— {@code restrictMode=1} 时必须配
     * {@code authorizedBackends} 或 {@code authorizedTools} 之一(否则该 token 没有任何可调资源)。
     * 默认 restrictMode=1 严格模式(避免"创建完就能调所有"的疏漏)。
     */
    @Transactional
    public McpApiTokenCreatedVO create(Long userId, McpApiTokenCreateDTO dto) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        int restrictMode = dto.getRestrictMode() == null ? 1 : dto.getRestrictMode();
        // 严格模式必须配授权范围 —— 避免"创建完空权限 token 用户以为可用"的歧义
        if (restrictMode == 1) {
            int backendCount = dto.getAuthorizedBackends() == null ? 0 : dto.getAuthorizedBackends().size();
            int toolCount = dto.getAuthorizedTools() == null ? 0 : dto.getAuthorizedTools().size();
            if (backendCount == 0 && toolCount == 0) {
                throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                        "严格模式下必须至少配置 1 个授权 backend 或 tool");
            }
        }
        String plaintext = generatePlaintext();
        String prefix = tokenDisplayPrefix(plaintext);
        String hash = tokenHash(plaintext);
        List<String> ipWhitelist = normalizeIpWhitelist(dto.getIpWhitelist());
        validateIpWhitelist(ipWhitelist);

        McpApiTokenDO t = new McpApiTokenDO();
        t.setUserId(userId);
        t.setName(dto.getName());
        t.setTokenPrefix(prefix);
        t.setTokenHash(hash);
        t.setScopes(JsonUtil.toJson(normalizeScopes(dto.getScopes())));
        t.setIpWhitelist(JsonUtil.toJson(ipWhitelist));
        t.setExpiresAt(dto.getExpiresAt());
        t.setStatus(1);
        t.setCreatedAt(LocalDateTime.now());
        t.setRestrictMode(restrictMode);
        tokenMapper.insert(t);

        // 写授权中间表(2026-07-03 加)
        if (dto.getAuthorizedBackends() != null && !dto.getAuthorizedBackends().isEmpty()) {
            for (String backendId : dto.getAuthorizedBackends()) {
                McpTokenBackendAuthorizationDO row = new McpTokenBackendAuthorizationDO();
                row.setTokenId(t.getId());
                row.setBackendId(backendId);
                row.setCreatedAt(LocalDateTime.now());
                tokenBackendAuthMapper.insert(row);
            }
        }
        if (dto.getAuthorizedTools() != null && !dto.getAuthorizedTools().isEmpty()) {
            for (McpApiTokenCreateDTO.AuthorizedToolRef ref : dto.getAuthorizedTools()) {
                McpTokenToolAuthorizationDO row = new McpTokenToolAuthorizationDO();
                row.setTokenId(t.getId());
                row.setBackendId(ref.getBackendId());
                row.setToolName(ref.getToolName());
                row.setCreatedAt(LocalDateTime.now());
                tokenToolAuthMapper.insert(row);
            }
        }

        log.info("[token-create] userId={} id={} prefix={} name={} restrictMode={} ipWhitelist={} backends={} tools={}",
                userId, t.getId(), prefix, dto.getName(), restrictMode,
                ipWhitelist.size(),
                dto.getAuthorizedBackends() == null ? 0 : dto.getAuthorizedBackends().size(),
                dto.getAuthorizedTools() == null ? 0 : dto.getAuthorizedTools().size());

        return McpApiTokenCreatedVO.builder()
                .id(String.valueOf(t.getId()))
                .name(dto.getName())
                .tokenPrefix(prefix)
                .plaintext(plaintext)
                .expiresAt(dto.getExpiresAt())
                .createdAt(t.getCreatedAt())
                .build();
    }

/**
     * 列出当前用户的所有 token(不含 hash 字段)。
     *
     * <p>按创建时间倒序 —— 最新的在前面。
     *
     * <p>2026-07-03 改:N+1 优化 —— 一次性查出所有 token 的 backend / tool 授权,
     * 内存里 groupBy 到 tokenId(避免每个 token 单独查 2 次 DB)。
     */
    public List<McpApiTokenVO> list(Long userId) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        List<McpApiTokenDO> rows = tokenMapper.selectList(new LambdaQueryWrapper<McpApiTokenDO>()
                .eq(McpApiTokenDO::getUserId, userId)
                .orderByDesc(McpApiTokenDO::getCreatedAt));
        if (rows.isEmpty()) {
            return List.of();
        }
        // 一次性查所有 token 的授权(2026-07-03 加)
        List<Long> tokenIds = rows.stream().map(McpApiTokenDO::getId).toList();
        Map<Long, List<String>> backendByToken = new HashMap<>();
        for (var pair : tokenBackendAuthMapper.selectPairsByTokenIds(tokenIds)) {
            backendByToken.computeIfAbsent(pair.tokenId(), k -> new ArrayList<>()).add(pair.backendId());
        }
        Map<Long, List<McpApiTokenVO.AuthorizedToolRef>> toolByToken = new HashMap<>();
        for (var triple : tokenToolAuthMapper.selectTriplesByTokenIds(tokenIds)) {
            toolByToken
                    .computeIfAbsent(triple.tokenId(), k -> new ArrayList<>())
                    .add(McpApiTokenVO.AuthorizedToolRef.builder()
                            .backendId(triple.backendId())
                            .toolName(triple.toolName())
                            .build());
        }
        List<McpApiTokenVO> out = new ArrayList<>(rows.size());
        for (McpApiTokenDO t : rows) {
            McpApiTokenVO vo = toVO(t);
            vo.setAuthorizedBackends(backendByToken.getOrDefault(t.getId(), List.of()));
            vo.setAuthorizedTools(toolByToken.getOrDefault(t.getId(), List.of()));
            out.add(vo);
        }
        return out;
    }

    /**
     * 撤销 token —— 软删({@code status=0} + revoked_at)。
     *
     * <p>撤销后 token 立即失效,但 DB 记录保留(给审计 / 数据恢复用)。
     */
    @Transactional
    public void revoke(Long userId, Long tokenId) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        McpApiTokenDO exist = tokenMapper.selectById(tokenId);
        if (exist == null || !exist.getUserId().equals(userId)) {
            throw new BizException(BizErrorCode.NOT_FOUND, "token 不存在或无权访问");
        }
        if (exist.getStatus() == 0) {
            return; // 已经是 revoked,幂等
        }
        McpApiTokenDO u = new McpApiTokenDO();
        u.setId(tokenId);
        u.setStatus(0);
        u.setRevokedAt(LocalDateTime.now());
        tokenMapper.updateById(u);
        log.info("[token-revoke] userId={} id={} prefix={}", userId, tokenId, exist.getTokenPrefix());
    }

    /**
     * 硬删 token —— 直接 DELETE,不可恢复。给"撤销后再清理记录"的场景用。
     */
    @Transactional
    public void hardDelete(Long userId, Long tokenId) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        McpApiTokenDO exist = tokenMapper.selectById(tokenId);
        if (exist == null || !exist.getUserId().equals(userId)) {
            throw new BizException(BizErrorCode.NOT_FOUND, "token 不存在或无权访问");
        }
        tokenMapper.deleteById(tokenId);
        log.info("[token-hard-delete] userId={} id={} prefix={}", userId, tokenId, exist.getTokenPrefix());
    }

    /**
     * 凭明文 token 解析出 userId + 写使用痕迹(给 TokenAuthInterceptor 用)。
     *
     * @return 有效时返 userId;无效返 null(不抛异常 —— interceptor 决定要不要返 401)
     */
    public Long resolveUserId(String plaintext, String remoteIp) {
        AuthResult r = resolveAuth(plaintext, remoteIp);
        return r == null ? null : r.userId();
    }

    /**
     * 2026-07-02 加:返 (userId, tokenId) —— 标准 MCP 端点 /mcp 创建 session 时
     * 需要把 token.id 跟 session 关联(方便审计/撤销时连带清 session)。
     * 不破坏老 resolveUserId 兼容。
     */
    public AuthResult resolveAuth(String plaintext, String remoteIp) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        if (!plaintext.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        if (plaintext.length() != TOKEN_PREFIX.length() + TOKEN_RANDOM_LENGTH) {
            return null;
        }
        McpApiTokenDO candidate = tokenMapper.selectActiveByHash(tokenHash(plaintext));
        if (candidate == null) {
            return null;
        }
        // 状态 + 过期检查
        if (candidate.getStatus() == null || candidate.getStatus() != 1) {
            return null;
        }
        if (candidate.getExpiresAt() != null && candidate.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        List<String> ipWhitelist = parseIpWhitelist(candidate.getIpWhitelist());
        if (!ipAllowed(remoteIp, ipWhitelist)) {
            log.info("[token-auth] ip denied: tokenId={} prefix={} ip={} whitelistSize={}",
                    candidate.getId(), candidate.getTokenPrefix(), remoteIp, ipWhitelist.size());
            return null;
        }
        // 2026-07-03 加:token 鉴权通过后再校验 user.status —— 账号被禁用时该用户的所有 token 立即失效。
        // 之前 token 跟 user.status 解耦,admin 禁用账号后 token 仍然能用,这是核心 bug。
        McpUserDO owner = userMapper.selectById(candidate.getUserId());
        if (owner == null || owner.getStatus() == null || owner.getStatus() != 1) {
            log.info("[token-auth] owner user disabled/removed: tokenId={} userId={} userStatus={}",
                    candidate.getId(), candidate.getUserId(),
                    owner == null ? "null" : owner.getStatus());
            return null;
        }
        updateUsage(candidate.getId(), remoteIp);
        return new AuthResult(candidate.getUserId(), candidate.getId(), owner.getEmail(), candidate.getTokenPrefix());
    }

    /**
     * 鉴权结果(2026-07-02 加)—— record 包 userId + tokenId。
     */
    public record AuthResult(Long userId, Long tokenId, String userEmail, String tokenPrefix) {
    }

    // ========== 私有工具方法 ==========

    /**
     * 生成完整 token 明文:{@code imwx_<32 位 base62 随机>}。
     *
     * <p>用 SecureRandom(密码学随机),不是 {@code new Random()} —— 后者能预测。
     */
    private static String generatePlaintext() {
        StringBuilder sb = new StringBuilder(TOKEN_PREFIX.length() + TOKEN_RANDOM_LENGTH);
        sb.append(TOKEN_PREFIX);
        for (int i = 0; i < TOKEN_RANDOM_LENGTH; i++) {
            sb.append(BASE62_CHARS[RANDOM.nextInt(BASE62_CHARS.length)]);
        }
        return sb.toString();
    }

    private static String tokenDisplayPrefix(String plaintext) {
        return plaintext.substring(0, Math.min(TOKEN_DISPLAY_PREFIX_LENGTH, plaintext.length()));
    }

    private static String tokenHash(String plaintext) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 作用域归一化:null → 默认 ["read","write"];非空 → 去空去重保留原顺序。
     */
    private static List<String> normalizeScopes(List<String> input) {
        if (input == null || input.isEmpty()) {
            return Arrays.asList("read", "write");
        }
        List<String> out = new ArrayList<>(input.size());
        for (String s : input) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (!out.contains(v)) out.add(v);
        }
        return out.isEmpty() ? Arrays.asList("read", "write") : Collections.unmodifiableList(out);
    }

    private static List<String> normalizeIpWhitelist(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : input) {
            if (item == null) {
                continue;
            }
            for (String part : item.split("[,;\\n\\r]+")) {
                String v = part.trim();
                if (!v.isEmpty() && v.length() <= 64) {
                    out.add(v);
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<String> parseIpWhitelist(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = JsonUtil.fromJson(json, new TypeReference<List<String>>() {});
            return normalizeIpWhitelist(values);
        } catch (Exception ignored) {
            return normalizeIpWhitelist(List.of(json));
        }
    }

    private static void validateIpWhitelist(List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return;
        }
        for (String rule : whitelist) {
            try {
                new IpAddressMatcher(rule);
            } catch (IllegalArgumentException e) {
                throw new BizException(BizErrorCode.INVALID_ARGUMENT, "IP 白名单格式不合法: " + rule);
            }
        }
    }

    private static boolean ipAllowed(String remoteIp, List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return true;
        }
        if (remoteIp == null || remoteIp.isBlank()) {
            return false;
        }
        String ip = remoteIp.trim();
        for (String rule : whitelist) {
            try {
                if (new IpAddressMatcher(rule).matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                if (rule.equals(ip)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * DO → VO —— 不带 tokenHash 字段(防密文外泄)。
     */
    private McpApiTokenVO toVO(McpApiTokenDO t) {
        List<String> scopes = parseScopes(t.getScopes());
        return McpApiTokenVO.builder()
                .id(String.valueOf(t.getId()))
                .name(t.getName())
                .tokenPrefix(t.getTokenPrefix())
                .scopes(scopes)
                .ipWhitelist(parseIpWhitelist(t.getIpWhitelist()))
                .expiresAt(t.getExpiresAt())
                .lastUsedAt(t.getLastUsedAt())
                .lastUsedIp(t.getLastUsedIp())
                .status(t.getStatus() != null && t.getStatus() == 1 ? "active" : "revoked")
                .createdAt(t.getCreatedAt())
                .revokedAt(t.getRevokedAt())
                .restrictMode(t.getRestrictMode() == null ? 0 : t.getRestrictMode())
                .authorizedBackends(List.of())  // 由 list() 批量回填(避免 N+1)
                .authorizedTools(List.of())
                .build();
    }

    /**
     * 单独更新 token 授权范围(2026-07-03 加)—— 不重建 token,纯改授权。
     *
     * <p>三段式更新:
     * <ol>
     *   <li>{@code restrictMode}:null = 不改;非 null = 直接覆盖</li>
     *   <li>{@code authorizedBackends}:null = 不改;非 null(可空)= 完整替换(DELETE + INSERT)</li>
     *   <li>{@code authorizedTools}:null = 不改;非 null(可空)= 完整替换</li>
     * </ol>
     *
     * <p>这样前端"修改授权"不需要 revoke 旧 token 重新生成,避免泄露新明文。
     */
    @Transactional
    public void updateAuthorizations(Long userId, Long tokenId, McpApiTokenAuthorizationsUpdateDTO dto) {
        if (userId == null) {
            throw new BizException(BizErrorCode.UNAUTHORIZED, "未登录");
        }
        McpApiTokenDO exist = tokenMapper.selectById(tokenId);
        if (exist == null || !exist.getUserId().equals(userId)) {
            throw new BizException(BizErrorCode.NOT_FOUND, "token 不存在或无权访问");
        }
        // 1. 更新 restrictMode
        if (dto.getRestrictMode() != null) {
            int newMode = dto.getRestrictMode();
            // 切严格模式时必须至少有 1 个授权 —— 避免"刚切严格就全部无权"的歧义
            if (newMode == 1 && dto.getAuthorizedBackends() == null && dto.getAuthorizedTools() == null) {
                int existingBackendCount = tokenBackendAuthMapper.selectBackendIdsByTokenId(tokenId).size();
                int existingToolCount = tokenToolAuthMapper.selectPairsByTokenId(tokenId).size();
                if (existingBackendCount == 0 && existingToolCount == 0) {
                    throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                            "切严格模式必须至少有 1 个授权 backend 或 tool");
                }
            }
            McpApiTokenDO u = new McpApiTokenDO();
            u.setId(tokenId);
            u.setRestrictMode(newMode);
            tokenMapper.updateById(u);
        }
        if (dto.getIpWhitelist() != null) {
            List<String> ipWhitelist = normalizeIpWhitelist(dto.getIpWhitelist());
            validateIpWhitelist(ipWhitelist);
            McpApiTokenDO u = new McpApiTokenDO();
            u.setId(tokenId);
            u.setIpWhitelist(JsonUtil.toJson(ipWhitelist));
            tokenMapper.updateById(u);
        }
        // 2. 替换 backend 授权(完整覆盖)
        if (dto.getAuthorizedBackends() != null) {
            tokenBackendAuthMapper.deleteByTokenId(tokenId);
            for (String backendId : dto.getAuthorizedBackends()) {
                if (backendId == null || backendId.isBlank()) continue;
                McpTokenBackendAuthorizationDO row = new McpTokenBackendAuthorizationDO();
                row.setTokenId(tokenId);
                row.setBackendId(backendId);
                row.setCreatedAt(LocalDateTime.now());
                tokenBackendAuthMapper.insert(row);
            }
        }
        // 3. 替换 tool 授权(完整覆盖)
        if (dto.getAuthorizedTools() != null) {
            tokenToolAuthMapper.deleteByTokenId(tokenId);
            for (McpApiTokenCreateDTO.AuthorizedToolRef ref : dto.getAuthorizedTools()) {
                if (ref == null || ref.getBackendId() == null || ref.getToolName() == null) continue;
                McpTokenToolAuthorizationDO row = new McpTokenToolAuthorizationDO();
                row.setTokenId(tokenId);
                row.setBackendId(ref.getBackendId());
                row.setToolName(ref.getToolName());
                row.setCreatedAt(LocalDateTime.now());
                tokenToolAuthMapper.insert(row);
            }
        }
        log.info("[token-auth-update] userId={} tokenId={} mode={} ipWhitelist={} backends={} tools={}",
                userId, tokenId,
                dto.getRestrictMode() == null ? "unchanged" : dto.getRestrictMode(),
                dto.getIpWhitelist() == null ? "unchanged" : normalizeIpWhitelist(dto.getIpWhitelist()).size(),
                dto.getAuthorizedBackends() == null ? "unchanged" : dto.getAuthorizedBackends().size(),
                dto.getAuthorizedTools() == null ? "unchanged" : dto.getAuthorizedTools().size());
    }

    /**
     * 校验 token 是否被授权访问某 backend(2026-07-03 加)。
     *
     * <p>严格模式(restrictMode=1):必须有授权记录
     * <ul>
     *   <li>backend 级授权命中 → true</li>
     *   <li>tool 级授权里有该 backend 下的任何 tool → true(隐式 backend 访问)</li>
     *   <li>都不命中 → false</li>
     * </ul>
     *
     * <p>非严格模式(restrictMode=0):全开放 → 永远 true
     *
     * <p>给阶段 3 聚合路由 /mcp 调 tools/list 时用 —— 决定哪些 backend 的 tool
     * 应该被这个 token 看到。
     */
    public boolean hasBackendAccess(Long tokenId, String backendId) {
        if (tokenId == null) return false;
        McpApiTokenDO token = tokenMapper.selectById(tokenId);
        if (token == null) return false;
        int mode = token.getRestrictMode() == null ? 0 : token.getRestrictMode();
        if (mode == 0) return true;
        // 严格模式:backend 在 backend 授权表 OR tool 授权表有该 backend 的 tool
        List<String> backends = tokenBackendAuthMapper.selectBackendIdsByTokenId(tokenId);
        if (backends.contains(backendId)) return true;
        List<McpTokenToolAuthorizationMapper.TokenToolPair> tools =
                tokenToolAuthMapper.selectPairsByTokenId(tokenId);
        for (var p : tools) {
            if (backendId.equals(p.backendId())) return true;
        }
        return false;
    }

    /**
     * 校验 token 是否被授权调用某 tool(2026-07-03 加)。
     *
     * <p>严格模式:必须 (backendId, toolName) 命中授权列表
     * <ul>
     *   <li>backend 级授权命中 → true(该 backend 全部 tool 都能调)</li>
     *   <li>tool 级精确匹配 → true</li>
     *   <li>都不命中 → false</li>
     * </ul>
     *
     * <p>非严格模式:全开放 → 永远 true
     */
    public boolean hasToolAccess(Long tokenId, String backendId, String toolName) {
        if (tokenId == null) return false;
        McpApiTokenDO token = tokenMapper.selectById(tokenId);
        if (token == null) return false;
        int mode = token.getRestrictMode() == null ? 0 : token.getRestrictMode();
        if (mode == 0) return true;
        // backend 级命中 = 整个 backend 都能调
        List<String> backends = tokenBackendAuthMapper.selectBackendIdsByTokenId(tokenId);
        if (backends.contains(backendId)) return true;
        // tool 级精确匹配
        return tokenToolAuthMapper.countByTriple(tokenId, backendId, toolName) > 0;
    }

    /**
     * 拿 token 的所有授权 backend id(2026-07-03 加)—— 给阶段 3 聚合路由过滤用。
     * null 表示全开放(无限制)。
     *
     * <p>2026-07-03 修:严格模式下,backend 集合 =
     * {@code backend_auth ∪ tool_auth.backend_id}。
     * 原实现只查 {@code backend_auth},导致"只配了 authorizedTools 不配 authorizedBackends"
     * 的 token 在 {@code tools/list} 返空(用户配的 tool 永远不出现)——
     * 跟 {@link #hasToolAccess} 的 OR 语义不一致,后者 tools/call 路径能通。
     * 现在 tools/list / tools/call 两侧的"backend 是否被授权"判断完全对齐。
     */
    public List<String> getAuthorizedBackendIds(Long tokenId) {
        if (tokenId == null) return null;
        McpApiTokenDO token = tokenMapper.selectById(tokenId);
        if (token == null) return null;
        int mode = token.getRestrictMode() == null ? 0 : token.getRestrictMode();
        if (mode == 0) return null; // null = 全开放
        // LinkedHashSet 保序;数据量小(单 token 授权 ≤ 几十个 backend),union 开销可忽略
        Set<String> result = new LinkedHashSet<>(tokenBackendAuthMapper.selectBackendIdsByTokenId(tokenId));
        for (McpTokenToolAuthorizationMapper.TokenToolPair p :
                tokenToolAuthMapper.selectPairsByTokenId(tokenId)) {
            result.add(p.backendId());
        }
        return new ArrayList<>(result);
    }

    /**
     * scopes JSON 字符串 → List<String>,解析失败返 ["read","write"]。
     */
    @SuppressWarnings("unchecked")
    private static List<String> parseScopes(String json) {
        if (json == null || json.isBlank()) {
            return Arrays.asList("read", "write");
        }
        try {
            List<String> parsed = JsonUtil.fromJson(json, new tools.jackson.core.type.TypeReference<List<String>>() {});
            return parsed == null ? Arrays.asList("read", "write") : parsed;
        } catch (Exception e) {
            return Arrays.asList("read", "write");
        }
    }

    private void updateUsage(Long tokenId, String remoteIp) {
        try {
            McpApiTokenDO u = new McpApiTokenDO();
            u.setId(tokenId);
            u.setLastUsedAt(LocalDateTime.now());
            u.setLastUsedIp(remoteIp);
            tokenMapper.updateById(u);
        } catch (Exception e) {
            log.warn("[token-usage] update failed: {}", e.getMessage());
        }
    }

    /** TokenAuthInterceptor 验证通过后写入 request attribute 的鉴权标记。 */
    public static final String AUTH_VIA_TOKEN = "imawx.authViaToken";

    public static final String TOKEN_ID = "imawx.tokenId";
}
