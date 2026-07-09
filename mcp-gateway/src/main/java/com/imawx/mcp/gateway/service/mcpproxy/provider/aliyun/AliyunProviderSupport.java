package com.imawx.mcp.gateway.service.mcpproxy.provider.aliyun;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 阿里云内置 provider 的配置与安全边界工具。
 */
public abstract class AliyunProviderSupport {

    protected final McpBackendService backendService;
    protected final McpBackendExtensionService extensionService;

    protected AliyunProviderSupport(McpBackendService backendService, McpBackendExtensionService extensionService) {
        this.backendService = backendService;
        this.extensionService = extensionService;
    }

    protected List<McpBackendDO> listEnabledBackends(String transportType) {
        return backendService.listEnabled().stream()
                .filter(b -> transportType.equalsIgnoreCase(b.getTransportType()))
                .toList();
    }

    protected McpBackendDO backend(String backendId) {
        McpBackendDO backend = backendService.selectByBackendId(backendId);
        if (backend == null || backend.getEnabled() == null || backend.getEnabled() != 1) {
            throw new BizException(BizErrorCode.NOT_FOUND, "阿里云 MCP 实例不存在或未启用: " + backendId);
        }
        return backend;
    }

    protected Map<String, Object> extra(McpBackendDO backend) {
        return extensionService.config(backend.getId());
    }

    protected Map<String, Object> secret(McpBackendDO backend) {
        return extensionService.secret(backend.getId());
    }

    protected String accessKeyId(McpBackendDO backend) {
        return str(extra(backend), "accessKeyId");
    }

    protected String accessKeySecret(McpBackendDO backend) {
        return str(secret(backend), "accessKeySecret");
    }

    protected String securityToken(McpBackendDO backend) {
        return str(secret(backend), "securityToken");
    }

    protected void requireCredentials(McpBackendDO backend) {
        if (blank(accessKeyId(backend)) || blank(accessKeySecret(backend))) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                    "阿里云 AK/SK 未配置: extraConfig.accessKeyId + authToken(AccessKeySecret)");
        }
    }

    protected String endpoint(McpBackendDO backend, String defaultValue) {
        String v = firstNonBlank(backend.getEndpoint(), str(extra(backend), "endpoint"));
        return blank(v) ? defaultValue : v.trim();
    }

    protected void requireAllowed(String value, Set<String> allowed, String label) {
        if (blank(value)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, label + " 不能为空");
        }
        if (!allowed.isEmpty() && !allowed.contains(value)) {
            throw new BizException(BizErrorCode.FORBIDDEN, label + " 不在白名单内: " + value);
        }
    }

    protected McpProviderCallResult textResult(Object data) {
        return new McpProviderCallResult(
                List.of(Map.of("type", "text", "text", JsonUtil.toJson(data))),
                false,
                Map.of("provider", "aliyun"));
    }

    protected static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : Objects.toString(v).trim();
    }

    protected static String requireString(Map<String, Object> args, String key) {
        String v = str(args, key);
        if (blank(v)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, key + " 不能为空");
        }
        return v;
    }

    protected static int intArg(Map<String, Object> args, String key, int defaultValue, int min, int max) {
        Object v = args == null ? null : args.get(key);
        int n = defaultValue;
        if (v instanceof Number num) {
            n = num.intValue();
        } else if (v instanceof String s && !s.isBlank()) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                n = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, n));
    }

    protected static long longArg(Map<String, Object> args, String key, long defaultValue, long min, long max) {
        Object v = args == null ? null : args.get(key);
        long n = defaultValue;
        if (v instanceof Number num) {
            n = num.longValue();
        } else if (v instanceof String s && !s.isBlank()) {
            try {
                n = Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                n = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, n));
    }

    protected static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    protected static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!blank(v)) return v.trim();
        }
        return null;
    }

    protected static String upper(String v) {
        return v == null ? null : v.trim().toUpperCase(Locale.ROOT);
    }
}
