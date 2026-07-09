package com.imawx.mcp.gateway.service.mcpproxy.provider.openapi;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.entity.dto.McpBackendCreateDTO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderServer;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

@Component
public class OpenApiHttpApiMcpProvider implements McpProvider {

    private static final String TRANSPORT_TYPE = "OPENAPI";
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private final McpBackendService backendService;
    private final McpBackendExtensionService extensionService;
    private final ConcurrentMap<String, CachedSpec> specCache = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public OpenApiHttpApiMcpProvider(McpBackendService backendService, McpBackendExtensionService extensionService) {
        this.backendService = backendService;
        this.extensionService = extensionService;
    }

    @Override
    public String serverType() {
        return TRANSPORT_TYPE;
    }

    @Override
    public List<McpProviderServer> listEnabledServers() {
        return backendService.listEnabled().stream()
                .filter(b -> TRANSPORT_TYPE.equalsIgnoreCase(b.getTransportType()))
                .map(b -> new McpProviderServer(TRANSPORT_TYPE, b.getBackendId(), b.getServerName(), true))
                .toList();
    }

    @Override
    public List<McpProviderTool> listTools(String serverId) {
        McpBackendDO backend = backendService.selectByBackendId(serverId);
        if (backend == null || !TRANSPORT_TYPE.equalsIgnoreCase(backend.getTransportType())) {
            return List.of();
        }
        OpenApiSpec spec = loadSpec(extensionService.config(backend.getId()), extensionService.secret(backend.getId()));
        return spec.operations().stream()
                .map(op -> new McpProviderTool(op.toolName(), op.description(), JsonUtil.toJson(op.inputSchema())))
                .toList();
    }

    public List<SysToolPreview> previewTools(McpBackendCreateDTO dto) {
        Map<String, Object> config = parseConfig(dto.getExtraConfig());
        Map<String, Object> secret = new LinkedHashMap<>();
        if (dto.getAuthToken() != null && !dto.getAuthToken().isBlank()) {
            secret.put("authToken", dto.getAuthToken().trim());
        }
        OpenApiSpec spec = loadSpec(config, secret);
        return spec.operations().stream()
                .map(op -> new SysToolPreview(op.toolName(), op.description(), JsonUtil.toJson(op.inputSchema())))
                .toList();
    }

    @Override
    public McpProviderCallResult callTool(McpProviderCallRequest request) {
        McpBackendDO backend = backendService.selectByBackendId(request.serverId());
        if (backend == null || !TRANSPORT_TYPE.equalsIgnoreCase(backend.getTransportType())) {
            throw new BizException(BizErrorCode.NOT_FOUND, "OpenAPI MCP 不存在: " + request.serverId());
        }
        Map<String, Object> config = extensionService.config(backend.getId());
        Map<String, Object> secret = extensionService.secret(backend.getId());
        OpenApiSpec spec = loadSpec(config, secret);
        Operation op = spec.operations().stream()
                .filter(item -> item.toolName().equals(request.toolName()))
                .findFirst()
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "OpenAPI tool 不存在: " + request.toolName()));
        return invoke(spec, op, request.arguments() == null ? Map.of() : request.arguments(), config, secret);
    }

    private McpProviderCallResult invoke(OpenApiSpec spec, Operation op, Map<String, Object> args,
                                         Map<String, Object> config, Map<String, Object> secret) {
        String path = op.path();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Parameter p : op.parameters()) {
            Object raw = args.get(p.name());
            if (raw == null) {
                if (p.required()) {
                    throw new BizException(BizErrorCode.INVALID_ARGUMENT, p.name() + " 不能为空");
                }
                continue;
            }
            String value = Objects.toString(raw);
            switch (p.in()) {
                case "path" -> path = path.replace("{" + p.name() + "}", encodePath(value));
                case "query" -> query.put(p.name(), value);
                case "header" -> headers.put(p.name(), value);
                default -> { }
            }
        }
        String url = joinUrl(spec.baseUrl(), path) + queryString(query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(intValue(config.get("timeoutSeconds"), 30)))
                .header("Accept", "application/json");
        headers.forEach(builder::header);
        applyAuth(builder, config, secret);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        if (op.hasRequestBody()) {
            Object bodyArg = args.get("body");
            if (bodyArg == null) {
                bodyArg = remainingBody(args, op.parameters());
            }
            body = HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(bodyArg == null ? Map.of() : bodyArg));
            builder.header("Content-Type", "application/json");
        }
        builder.method(op.method().toUpperCase(Locale.ROOT), body);
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", response.statusCode());
            out.put("url", url);
            out.put("method", op.method().toUpperCase(Locale.ROOT));
            Object parsed = JsonUtil.fromJson(response.body(), new TypeReference<Object>() {});
            out.put("data", parsed == null ? response.body() : parsed);
            return new McpProviderCallResult(List.of(Map.of("type", "text", "text", JsonUtil.toJson(out))), !ok,
                    Map.of("httpStatus", response.statusCode()));
        } catch (IOException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "OpenAPI 转发网络失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "OpenAPI 转发被中断");
        }
    }

    private OpenApiSpec loadSpec(Map<String, Object> config, Map<String, Object> secret) {
        String cacheKey = cacheKey(config, secret);
        long now = System.currentTimeMillis();
        int ttlSeconds = Math.max(0, intValue(config.get("specCacheSeconds"), 60));
        CachedSpec cached = specCache.get(cacheKey);
        if (cached != null && ttlSeconds > 0 && cached.expiresAtMillis() > now) {
            return cached.spec();
        }
        String specUrl = firstNonBlank(str(config.get("specUrl")), str(config.get("swaggerUrl")), str(config.get("openapiUrl")));
        if (specUrl == null) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "OpenAPI 文档地址不能为空");
        }
        backendService.validateBackendTarget(TRANSPORT_TYPE, specUrl);
        Map<String, Object> root = fetchSpec(specUrl, config, secret);
        String baseUrl = firstNonBlank(str(config.get("baseUrl")), inferBaseUrl(root, specUrl));
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "OpenAPI Base URL 不能为空");
        }
        backendService.validateBackendTarget(TRANSPORT_TYPE, baseUrl);
        List<Operation> operations = parseOperations(root, config);
        if (operations.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "OpenAPI 没有可暴露的接口，请检查 allowedMethods/operationAllowlist");
        }
        OpenApiSpec spec = new OpenApiSpec(baseUrl, operations);
        if (ttlSeconds > 0) {
            specCache.put(cacheKey, new CachedSpec(spec, now + ttlSeconds * 1000L));
        }
        return spec;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSpec(String specUrl, Map<String, Object> config, Map<String, Object> secret) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(specUrl.trim()))
                .timeout(Duration.ofSeconds(intValue(config.get("timeoutSeconds"), 30)))
                .header("Accept", "application/json, application/yaml, text/yaml, */*")
                .GET();
        if (boolValue(config.get("authForSpec"), true)) {
            applyAuth(builder, config, secret);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "OpenAPI 文档拉取失败: HTTP " + response.statusCode());
            }
            Object json = JsonUtil.fromJson(response.body(), new TypeReference<Object>() {});
            if (json instanceof Map<?, ?> map) {
                return normalizeMap(map);
            }
            Object yaml = new Yaml().load(response.body());
            if (yaml instanceof Map<?, ?> map) {
                return normalizeMap(map);
            }
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "OpenAPI 文档不是 JSON/YAML 对象");
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "OpenAPI 文档网络失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "OpenAPI 文档拉取被中断");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Operation> parseOperations(Map<String, Object> root, Map<String, Object> config) {
        Object pathsObj = root.get("paths");
        if (!(pathsObj instanceof Map<?, ?> paths)) {
            return List.of();
        }
        Set<String> allowedMethods = stringSet(config.get("allowedMethods"));
        if (allowedMethods.isEmpty()) {
            allowedMethods = Set.of("get");
        }
        Set<String> allowOps = stringSet(config.get("operationAllowlist"));
        Set<String> denyOps = stringSet(config.get("operationDenylist"));
        String pathPrefix = firstNonBlank(str(config.get("pathPrefix")));
        if (pathPrefix == null) {
            pathPrefix = "";
        }
        List<Operation> out = new ArrayList<>();
        for (Map.Entry<?, ?> pathEntry : paths.entrySet()) {
            String path = Objects.toString(pathEntry.getKey());
            if (!pathPrefix.isBlank() && !path.startsWith(pathPrefix)) {
                continue;
            }
            if (!(pathEntry.getValue() instanceof Map<?, ?> pathItemRaw)) {
                continue;
            }
            Map<String, Object> pathItem = normalizeMap(pathItemRaw);
            List<Parameter> commonParams = parseParameters(pathItem.get("parameters"), root);
            for (Map.Entry<String, Object> methodEntry : pathItem.entrySet()) {
                String method = methodEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method) || !allowedMethods.contains(method)) {
                    continue;
                }
                if (!(methodEntry.getValue() instanceof Map<?, ?> opRaw)) {
                    continue;
                }
                Map<String, Object> opMap = normalizeMap(opRaw);
                String operationId = firstNonBlank(str(opMap.get("operationId")), method + "_" + path.replaceAll("[^A-Za-z0-9]+", "_"));
                String toolName = safeToolName(operationId);
                String operationKey = operationId.toLowerCase(Locale.ROOT);
                String toolKey = toolName.toLowerCase(Locale.ROOT);
                if ((!allowOps.isEmpty() && !allowOps.contains(operationKey) && !allowOps.contains(toolKey))
                        || denyOps.contains(operationKey) || denyOps.contains(toolKey)) {
                    continue;
                }
                List<Parameter> params = new ArrayList<>(commonParams);
                params.addAll(parseParameters(opMap.get("parameters"), root));
                Map<String, Object> bodySchema = requestBodySchema(opMap.get("requestBody"), root);
                boolean hasBody = bodySchema != null && !bodySchema.isEmpty();
                Map<String, Object> inputSchema = inputSchema(params, bodySchema);
                String summary = firstNonBlank(str(opMap.get("summary")), str(opMap.get("description")), method.toUpperCase(Locale.ROOT) + " " + path);
                out.add(new Operation(toolName, method, path, summary, params, hasBody, inputSchema));
            }
        }
        return out;
    }

    private List<Parameter> parseParameters(Object obj, Map<String, Object> root) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        List<Parameter> out = new ArrayList<>();
        for (Object item : list) {
            Object resolved = resolveRef(item, root);
            if (!(resolved instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> p = normalizeMap(raw);
            String name = str(p.get("name"));
            String in = firstNonBlank(str(p.get("in")), "query");
            if (name == null || name.isBlank()) {
                continue;
            }
            Object schemaObj = resolveRef(p.get("schema"), root);
            Map<String, Object> schema = schemaObj instanceof Map<?, ?> sm ? normalizeMap(sm) : Map.of("type", "string");
            out.add(new Parameter(name, in, Boolean.TRUE.equals(p.get("required")), schema));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestBodySchema(Object obj, Map<String, Object> root) {
        Object resolved = resolveRef(obj, root);
        if (!(resolved instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> body = normalizeMap(raw);
        Object contentObj = body.get("content");
        if (contentObj instanceof Map<?, ?> content) {
            Object appJson = firstExisting(normalizeMap(content), "application/json", "application/*+json", "*/*");
            if (appJson instanceof Map<?, ?> mt) {
                Object schema = resolveRef(normalizeMap(mt).get("schema"), root);
                return schema instanceof Map<?, ?> sm ? normalizeMap(sm) : Map.of();
            }
        }
        Object schema = resolveRef(body.get("schema"), root);
        return schema instanceof Map<?, ?> sm ? normalizeMap(sm) : Map.of();
    }

    private Map<String, Object> inputSchema(List<Parameter> params, Map<String, Object> bodySchema) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter p : params) {
            properties.put(p.name(), p.schema());
            if (p.required()) {
                required.add(p.name());
            }
        }
        if (bodySchema != null && !bodySchema.isEmpty()) {
            properties.put("body", bodySchema);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    private Object resolveRef(Object obj, Map<String, Object> root) {
        if (!(obj instanceof Map<?, ?> raw)) {
            return obj;
        }
        Map<String, Object> map = normalizeMap(raw);
        String ref = str(map.get("$ref"));
        if (ref == null || !ref.startsWith("#/")) {
            return map;
        }
        Object current = root;
        for (String part : ref.substring(2).split("/")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return map;
            }
            current = normalizeMap(currentMap).get(part.replace("~1", "/").replace("~0", "~"));
        }
        return current == null ? map : current;
    }

    private String inferBaseUrl(Map<String, Object> root, String specUrl) {
        Object serversObj = root.get("servers");
        if (serversObj instanceof List<?> servers && !servers.isEmpty() && servers.getFirst() instanceof Map<?, ?> first) {
            String url = str(normalizeMap(first).get("url"));
            if (url != null && !url.isBlank()) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return url;
                }
                URI spec = URI.create(specUrl);
                return spec.getScheme() + "://" + spec.getAuthority() + (url.startsWith("/") ? url : "/" + url);
            }
        }
        String host = str(root.get("host"));
        if (host != null) {
            Object schemes = root.get("schemes");
            String scheme = schemes instanceof List<?> list && !list.isEmpty() ? Objects.toString(list.getFirst()) : "https";
            String basePath = firstNonBlank(str(root.get("basePath")), "");
            return scheme + "://" + host + basePath;
        }
        URI uri = URI.create(specUrl);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private void applyAuth(HttpRequest.Builder builder, Map<String, Object> config, Map<String, Object> secret) {
        String authType = firstNonBlank(str(config.get("authType")), "NONE").toUpperCase(Locale.ROOT);
        String token = firstNonBlank(str(secret.get("authToken")), str(secret.get("password")), str(secret.get("apiKey")));
        switch (authType) {
            case "BASIC" -> {
                String username = firstNonBlank(str(config.get("basicUsername")), str(config.get("username")), "");
                if (token != null) {
                    String basic = Base64.getEncoder().encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8));
                    builder.header("Authorization", "Basic " + basic);
                }
            }
            case "BEARER" -> {
                if (token != null) builder.header("Authorization", "Bearer " + token);
            }
            case "API_KEY_HEADER" -> {
                String header = firstNonBlank(str(config.get("apiKeyHeader")), "X-API-Key");
                if (token != null) builder.header(header, token);
            }
            default -> { }
        }
    }

    private static Map<String, Object> parseConfig(String json) {
        Map<String, Object> map = JsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
        return map == null ? Map.of() : map;
    }

    private static String safeToolName(String raw) {
        String value = raw == null ? "openapi_tool" : raw.replaceAll("[^A-Za-z0-9_]+", "_");
        value = value.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (value.isBlank()) value = "openapi_tool";
        if (!Pattern.matches("[A-Za-z_].*", value)) value = "op_" + value;
        return value.length() > 96 ? value.substring(0, 96) : value;
    }

    private static Object remainingBody(Map<String, Object> args, List<Parameter> params) {
        Set<String> paramNames = new LinkedHashSet<>();
        params.forEach(p -> paramNames.add(p.name()));
        Map<String, Object> out = new LinkedHashMap<>();
        args.forEach((k, v) -> {
            if (!paramNames.contains(k)) out.put(k, v);
        });
        return out.isEmpty() ? null : out;
    }

    private static String queryString(Map<String, String> query) {
        if (query.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(encodeQuery(e.getKey())).append('=').append(encodeQuery(e.getValue()));
        }
        return sb.toString();
    }

    private static String joinUrl(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Object firstExisting(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) return map.get(key);
        }
        return map.values().stream().findFirst().orElse(null);
    }

    private static Set<String> stringSet(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String s = str(item);
                if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        } else {
            String s = str(value);
            if (s != null) {
                for (String item : s.split("[,\\n\\r;]+")) {
                    if (!item.isBlank()) out.add(item.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(Objects.toString(k), v));
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return fallback;
    }

    private static String cacheKey(Map<String, Object> config, Map<String, Object> secret) {
        return Integer.toHexString(Objects.hash(JsonUtil.toJson(config), JsonUtil.toJson(secret)));
    }

    public record SysToolPreview(String name, String description, String inputSchema) {
    }

    private record OpenApiSpec(String baseUrl, List<Operation> operations) {
    }

    private record CachedSpec(OpenApiSpec spec, long expiresAtMillis) {
    }

    private record Operation(String toolName, String method, String path, String description,
                             List<Parameter> parameters, boolean hasRequestBody,
                             Map<String, Object> inputSchema) {
    }

    private record Parameter(String name, String in, boolean required, Map<String, Object> schema) {
    }
}
