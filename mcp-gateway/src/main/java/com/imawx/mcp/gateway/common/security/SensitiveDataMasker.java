package com.imawx.mcp.gateway.common.security;

import com.imawx.mcp.gateway.common.util.JsonUtil;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 敏感数据脱敏工具。
 */
public final class SensitiveDataMasker {

    public static final String CONFIG_REDACT_KEYS = "mcp.security.redact.keys";
    public static final String CONFIG_REDACT_PATTERNS = "mcp.security.redact.patterns";

    private static final String MASK = "***";
    private static final Set<String> DEFAULT_KEYS = Set.of(
            "password", "passwd", "pwd", "token", "access_token", "refresh_token",
            "authorization", "auth_token", "api_key", "apikey", "secret", "secret_key",
            "private_key", "client_secret", "totp_secret", "credential",
            "accesskey", "accesskeyid", "accesskeysecret", "access_key_id", "access_key_secret",
            "securitytoken", "security_token", "x-api-key", "api-key"
    );

    private static final AtomicReference<Rules> RULES =
            new AtomicReference<>(new Rules(DEFAULT_KEYS, List.of()));

    private SensitiveDataMasker() {
    }

    public static void configure(String keysConfig, String patternsConfig) {
        RULES.set(new Rules(parseKeys(keysConfig), parsePatterns(patternsConfig)));
    }

    public static String redactJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        Object value = JsonUtil.fromJson(json, new TypeReference<Object>() {});
        if (value == null) {
            return redactText(json);
        }
        return JsonUtil.toJson(redactValue(value));
    }

    public static String redactText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Rules rules = RULES.get();
        String out = text;
        for (String key : rules.keys()) {
            out = out.replaceAll("(?i)(\"" + Pattern.quote(key) + "\"\\s*:\\s*\")([^\"]*)(\")", "$1" + MASK + "$3");
            out = out.replaceAll("(?i)(\\b" + Pattern.quote(key) + "\\b\\s*[=:]\\s*)([^\\s,;&]+)", "$1" + MASK);
        }
        for (Pattern pattern : rules.patterns()) {
            out = pattern.matcher(out).replaceAll(MASK);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isSensitiveKey(key)) {
                    out.put(key, MASK);
                } else {
                    out.put(key, redactValue(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>(collection.size());
            for (Object item : collection) {
                out.add(redactValue(item));
            }
            return out;
        }
        if (value instanceof String s) {
            return redactText(s);
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (RULES.get().keys().contains(normalized)) {
            return true;
        }
        return normalized.endsWith("password")
                || normalized.endsWith("token")
                || normalized.endsWith("secret")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("apikey")
                || normalized.endsWith("api-key")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("accesskeyid")
                || normalized.endsWith("accesskeysecret");
    }

    private static Set<String> parseKeys(String config) {
        Set<String> keys = new LinkedHashSet<>(DEFAULT_KEYS);
        splitConfig(config).forEach(v -> keys.add(v.toLowerCase(Locale.ROOT)));
        return Set.copyOf(keys);
    }

    private static List<Pattern> parsePatterns(String config) {
        List<Pattern> patterns = new ArrayList<>();
        for (String item : splitConfig(config)) {
            try {
                patterns.add(Pattern.compile(item));
            } catch (PatternSyntaxException ignored) {
                // 配置表里的坏正则不能影响应用启动或配置刷新。
            }
        }
        return patterns;
    }

    private static List<String> splitConfig(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        return Pattern.compile("[,\\n\\r;]+")
                .splitAsStream(config)
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .toList();
    }

    private record Rules(Set<String> keys, List<Pattern> patterns) {
    }
}
