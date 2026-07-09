package com.imawx.mcp.gateway.service.mcpproxy.definition;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;
import com.imawx.mcp.gateway.service.mcpproxy.provider.annotation.McpToolDefinition;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class BuiltinMcpDefinitionService {
    private static final String TEMPLATE_RESOURCE = "mcp/builtin-templates.json";
    private static final String PROBE_RESOURCE = "mcp/builtin-probes.json";
    private static final String TRANSPORT_RESOURCE = "mcp/builtin-transports.json";

    private final ApplicationContext applicationContext;

    private List<BuiltinMcpTemplate> templates = List.of();
    private Map<String, List<McpProviderTool>> toolsByTransport = Map.of();
    private Map<String, BuiltinProbeDefinition> probesByTransport = Map.of();
    private Map<String, BuiltinTransportDefinition> transportsByType = Map.of();

    public BuiltinMcpDefinitionService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void load() {
        templates = List.copyOf(loadTemplates());
        Map<String, BuiltinProbeDefinition> loadedProbes = new LinkedHashMap<>();
        loadProbes().forEach((transport, probe) -> loadedProbes.put(normalize(transport), probe));
        probesByTransport = Map.copyOf(loadedProbes);
        transportsByType = Map.copyOf(resolveTransports(loadTransports()));
        log.info("[builtin-mcp-definition] loaded templates={} probeTransports={} transports={}",
                templates.size(), probesByTransport.keySet(), transportsByType.keySet());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAnnotatedTools() {
        Map<String, Map<String, McpProviderTool>> grouped = new LinkedHashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean = applicationContext.getBean(beanName);
            String providerTransport = bean instanceof McpProvider provider ? provider.serverType() : null;
            for (Method method : annotatedMethods(ClassUtils.getUserClass(bean))) {
                McpToolDefinition definition = method.getAnnotation(McpToolDefinition.class);
                List<String> transports = transports(definition, providerTransport);
                for (String transport : transports) {
                    grouped.computeIfAbsent(transport, ignored -> new LinkedHashMap<>());
                    McpProviderTool tool = new McpProviderTool(
                            definition.name(),
                            definition.description(),
                            enrichInputSchema(definition.name(), definition.inputSchema()));
                    McpProviderTool previous = grouped.get(transport).putIfAbsent(definition.name(), tool);
                    if (previous != null) {
                        throw new BizException(BizErrorCode.INTERNAL_ERROR,
                                "重复的 MCP tool 注解: transport=" + transport + ", tool=" + definition.name());
                    }
                }
            }
        }
        Map<String, List<McpProviderTool>> loaded = new LinkedHashMap<>();
        grouped.forEach((transport, tools) -> loaded.put(transport, List.copyOf(tools.values())));
        toolsByTransport = Map.copyOf(loaded);
        log.info("[builtin-mcp-definition] loaded annotated toolTransports={}", toolsByTransport.keySet());
    }

    public List<BuiltinMcpTemplate> templates() {
        return templates;
    }

    public List<McpProviderTool> tools(String transportType) {
        if (transportType == null) {
            return List.of();
        }
        String type = normalize(transportType);
        return toolsByTransport.getOrDefault(type, List.of());
    }

    public List<McpProviderTool> requireTools(String transportType) {
        List<McpProviderTool> tools = tools(transportType);
        if (tools.isEmpty()) {
            throw new BizException(BizErrorCode.INTERNAL_ERROR, "内置 MCP tool 定义未配置: " + transportType);
        }
        return tools;
    }

    public BuiltinProbeDefinition probe(String transportType) {
        if (transportType == null) {
            return null;
        }
        String type = normalize(transportType);
        return probesByTransport.get(type);
    }

    public BuiltinTransportDefinition transport(String transportType) {
        if (transportType == null) {
            return null;
        }
        return transportsByType.get(normalize(transportType));
    }

    public boolean supportsTransport(String transportType) {
        return transport(transportType) != null;
    }

    public Integer toolCount(String transportType) {
        if (transportType == null) {
            return null;
        }
        List<McpProviderTool> tools = tools(transportType);
        if (!tools.isEmpty()) {
            return tools.size();
        }
        String type = normalize(transportType);
        return templates.stream()
                .filter(t -> t.transportType() != null && normalize(t.transportType()).equals(type))
                .map(BuiltinMcpTemplate::toolCount)
                .filter(v -> v != null && v >= 0)
                .findFirst()
                .orElse(null);
    }

    private List<BuiltinMcpTemplate> loadTemplates() {
        List<BuiltinMcpTemplate> data = JsonUtil.fromJson(readResource(TEMPLATE_RESOURCE),
                new TypeReference<List<BuiltinMcpTemplate>>() {});
        return data == null ? List.of() : data;
    }

    private Map<String, BuiltinProbeDefinition> loadProbes() {
        Map<String, BuiltinProbeDefinition> data = JsonUtil.fromJson(readResource(PROBE_RESOURCE),
                new TypeReference<Map<String, BuiltinProbeDefinition>>() {});
        return data == null ? Map.of() : data;
    }

    private List<BuiltinTransportDefinition> loadTransports() {
        List<BuiltinTransportDefinition> data = JsonUtil.fromJson(readResource(TRANSPORT_RESOURCE),
                new TypeReference<List<BuiltinTransportDefinition>>() {});
        return data == null ? List.of() : data;
    }

    private Map<String, BuiltinTransportDefinition> resolveTransports(List<BuiltinTransportDefinition> definitions) {
        Map<String, BuiltinTransportDefinition> raw = new LinkedHashMap<>();
        for (BuiltinTransportDefinition definition : definitions) {
            raw.put(normalize(definition.transportType()), definition);
        }
        Map<String, BuiltinTransportDefinition> resolved = new LinkedHashMap<>();
        for (BuiltinTransportDefinition definition : definitions) {
            resolved.put(normalize(definition.transportType()), resolveTransport(definition, raw));
        }
        return resolved;
    }

    private BuiltinTransportDefinition resolveTransport(
            BuiltinTransportDefinition definition,
            Map<String, BuiltinTransportDefinition> raw) {
        if (definition.extendsTransport() == null || definition.extendsTransport().isBlank()) {
            return definition.normalized();
        }
        BuiltinTransportDefinition parent = raw.get(normalize(definition.extendsTransport()));
        if (parent == null) {
            throw new BizException(BizErrorCode.INTERNAL_ERROR,
                    "内置 MCP transport 继承目标不存在: " + definition.extendsTransport());
        }
        BuiltinTransportDefinition base = resolveTransport(parent, raw);
        return new BuiltinTransportDefinition(
                definition.transportType(),
                definition.extendsTransport(),
                firstNonNull(definition.extensionTransport(), base.extensionTransport()),
                firstNonBlank(definition.endpointPolicy(), base.endpointPolicy()),
                firstNonNull(definition.allowedSchemes(), base.allowedSchemes()),
                firstNonNull(definition.rejectUserInfo(), base.rejectUserInfo()),
                firstNonBlank(definition.endpointLabel(), base.endpointLabel()),
                firstNonBlank(definition.secretKey(), base.secretKey()),
                firstNonNull(definition.secretConfigKeys(), base.secretConfigKeys()),
                firstNonNull(definition.requiredConfig(), base.requiredConfig()),
                firstNonBlank(definition.secretRequiredMessage(), base.secretRequiredMessage()),
                firstNonNull(definition.secretRequiredWhenConfigNotEquals(), base.secretRequiredWhenConfigNotEquals()),
                firstNonNull(definition.resourceFields(), base.resourceFields())
        ).normalized();
    }

    private static String readResource(String location) {
        try {
            return new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(BizErrorCode.INTERNAL_ERROR, "读取内置 MCP 定义失败: " + location);
        }
    }

    private static String normalize(String transportType) {
        return transportType.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> transports(McpToolDefinition definition, String providerTransport) {
        if (definition.transports().length > 0) {
            return java.util.Arrays.stream(definition.transports())
                    .filter(v -> v != null && !v.isBlank())
                    .map(BuiltinMcpDefinitionService::normalize)
                    .toList();
        }
        if (providerTransport == null || providerTransport.isBlank()) {
            return List.of();
        }
        return List.of(normalize(providerTransport));
    }

    private static String enrichInputSchema(String toolName, String inputSchema) {
        String schemaText = inputSchema == null ? "" : inputSchema.strip();
        if (schemaText.isBlank()) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        Map<String, Object> schema = JsonUtil.fromJson(schemaText, new TypeReference<>() {
        });
        if (schema == null) {
            return schemaText;
        }
        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map<?, ?> props)) {
            return JsonUtil.toJson(schema);
        }
        for (Map.Entry<?, ?> entry : props.entrySet()) {
            Object specObj = entry.getValue();
            if (!(entry.getKey() instanceof String paramName) || !(specObj instanceof Map<?, ?> spec)) {
                continue;
            }
            if (hasText(spec.get("description"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> writable = (Map<String, Object>) spec;
            writable.put("description", parameterDescription(toolName, paramName));
        }
        return JsonUtil.toJson(schema);
    }

    private static String parameterDescription(String toolName, String paramName) {
        String tool = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        String param = paramName == null ? "" : paramName.trim();
        String key = param.toLowerCase(Locale.ROOT);
        String toolSpecific = toolSpecificParameterDescription(tool, key);
        if (hasText(toolSpecific)) {
            return toolSpecific;
        }
        return switch (key) {
            case "table" -> "目标表名，可先调用 list_tables 获取准确表名";
            case "sql" -> "要执行的 SQL 语句";
            case "limit" -> "返回行数上限，未传时使用默认值";
            case "values" -> "字段和值的 JSON 对象，key 为字段名，value 为要写入的新值";
            case "where", "filter" -> "过滤条件 JSON 对象；写操作必须填写，避免全表影响";
            case "key" -> "目标 key 或对象路径";
            case "keys" -> "目标 key 列表";
            case "field" -> "字段名";
            case "fields" -> "字段和值的 JSON 对象，或字段名数组，取决于当前 tool";
            case "value" -> "写入值";
            case "ttlseconds" -> "过期时间，单位秒";
            case "pattern" -> "匹配表达式，例如 * 或业务前缀*";
            case "cursor" -> "游标位置；首次查询可为空或使用 0";
            case "count" -> "本次扫描或读取数量上限";
            case "database" -> "数据库编号或数据库名；不填使用 MCP 实例默认库";
            case "collection" -> "MongoDB 集合名称，可先调用 mongo_list_collections 获取";
            case "projection" -> "MongoDB projection JSON，控制返回字段";
            case "pipeline" -> "MongoDB aggregation pipeline 数组";
            case "document" -> "要写入的文档 JSON 对象";
            case "update" -> "MongoDB update JSON，例如 {\"$set\":{\"status\":\"OK\"}}";
            case "index" -> "Elasticsearch 索引名称或别名";
            case "query" -> "查询 DSL JSON 对象";
            case "size" -> "返回文档数量上限";
            case "id" -> "文档 ID";
            case "doc" -> "要更新的文档局部字段 JSON 对象";
            case "upsert" -> "文档不存在时用于插入的 JSON 对象，可为空";
            case "bucket" -> "OSS bucket 名称；受实例允许范围限制";
            case "prefix" -> "对象 key 前缀或名称前缀，用于缩小列表范围";
            case "region" -> "阿里云地域 ID，例如 cn-hangzhou；不填使用实例默认地域";
            case "marker" -> "OSS 分页 marker；首次查询可为空";
            case "maxkeys" -> "最多返回对象数量";
            case "content" -> "写入对象的文本内容";
            case "contenttype" -> "对象 Content-Type，例如 text/plain; charset=utf-8";
            case "targetbucket" -> "复制目标 bucket；不填默认复制到当前 bucket";
            case "targetkey" -> "复制目标对象 key";
            case "expireseconds" -> "预签名 URL 有效期，单位秒";
            case "domainname" -> "域名，例如 example.com";
            case "rr", "rrkeyword" -> "主机记录，例如 www、api 或 @";
            case "type" -> "记录类型或数据类型，例如 A、CNAME、TXT";
            case "recordid" -> "DNS 解析记录 ID；更新或删除已有记录时优先使用";
            case "ttl" -> "DNS TTL，单位秒";
            case "line" -> "DNS 解析线路，默认 default";
            case "keyword" -> "搜索关键字";
            case "pagenumber" -> "页码，从 1 开始";
            case "pagesize" -> "每页数量";
            case "command" -> "要执行的 SSH 命令；如果实例配置了允许命令列表，必须命中白名单";
            case "buildnumber" -> "Drone 构建编号";
            case "branch" -> "Git 分支名";
            case "event" -> "Drone 触发事件，例如 push 或 promote";
            case "target" -> "Drone 目标环境或 promote target";
            case "intervalseconds" -> "轮询间隔，单位秒";
            case "timeoutseconds" -> "等待超时时间，单位秒";
            case "includelog" -> "是否同时返回构建日志";
            default -> param + " 参数";
        };
    }

    private static String toolSpecificParameterDescription(String tool, String key) {
        if (tool.startsWith("redis_")) {
            return switch (key) {
                case "member", "member1", "member2" -> "Redis Set/ZSet/Geo 成员名称";
                case "members" -> "Redis Set/ZSet 成员列表";
                case "start" -> "起始位置、起始 ID 或起始 offset";
                case "stop", "end" -> "结束位置、结束 ID 或结束 offset";
                case "score" -> "ZSet 分值";
                case "items" -> "批量写入对象，key 为成员，value 为分值";
                case "withscores" -> "是否返回 ZSet 分值";
                case "offset" -> "Bitmap bit 偏移量，从 0 开始";
                case "elements" -> "HyperLogLog 元素列表";
                case "longitude" -> "经度";
                case "latitude" -> "纬度";
                case "radius" -> "半径";
                case "unit" -> "距离单位：M、KM、MI 或 FT";
                default -> null;
            };
        }
        if (tool.startsWith("oss_")) {
            return switch (key) {
                case "key" -> "OSS 对象 key，即对象完整路径";
                case "maxbytes" -> "最多读取字节数，避免大对象撑爆响应";
                default -> null;
            };
        }
        if (tool.startsWith("mongo_")) {
            return switch (key) {
                case "field" -> "MongoDB 字段名，例如 status 或 user.id";
                default -> null;
            };
        }
        return null;
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().trim().isEmpty();
    }

    private static List<Method> annotatedMethods(Class<?> type) {
        List<Method> out = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(McpToolDefinition.class)) {
                    out.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return out;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value == null ? fallback : value;
    }

    public record BuiltinMcpTemplate(
            String key,
            String name,
            String description,
            String transportType,
            String endpoint,
            String extraConfig,
            String providerType,
            Integer toolCount,
            List<String> tags,
            String securityNote
    ) {
    }

    public record BuiltinProbeDefinition(
            String toolName,
            Map<String, Object> arguments
    ) {
        public Map<String, Object> safeArguments() {
            return arguments == null ? Map.of() : arguments;
        }
    }

    public record BuiltinTransportDefinition(
            String transportType,
            String extendsTransport,
            Boolean extensionTransport,
            String endpointPolicy,
            List<String> allowedSchemes,
            Boolean rejectUserInfo,
            String endpointLabel,
            String secretKey,
            List<String> secretConfigKeys,
            List<RequiredConfig> requiredConfig,
            String secretRequiredMessage,
            ConditionalSecretRequired secretRequiredWhenConfigNotEquals,
            List<ResourceField> resourceFields
    ) {
        public BuiltinTransportDefinition normalized() {
            return new BuiltinTransportDefinition(
                    normalize(transportType),
                    extendsTransport,
                    Boolean.TRUE.equals(extensionTransport),
                    endpointPolicy == null ? "PASS" : endpointPolicy.trim().toUpperCase(Locale.ROOT),
                    allowedSchemes == null ? List.of() : List.copyOf(allowedSchemes),
                    Boolean.TRUE.equals(rejectUserInfo),
                    endpointLabel,
                    secretKey,
                    secretConfigKeys == null ? List.of() : List.copyOf(secretConfigKeys),
                    requiredConfig == null ? List.of() : List.copyOf(requiredConfig),
                    secretRequiredMessage,
                    secretRequiredWhenConfigNotEquals,
                    resourceFields == null ? List.of() : List.copyOf(resourceFields)
            );
        }

        public boolean isExtensionTransport() {
            return Boolean.TRUE.equals(extensionTransport);
        }
    }

    public record RequiredConfig(
            String key,
            String type,
            String message
    ) {
    }

    public record ConditionalSecretRequired(
            String key,
            String value,
            String message
    ) {
    }

    public record ResourceField(
            String label,
            String source,
            List<String> keys,
            Boolean sanitize
    ) {
        public boolean shouldSanitize() {
            return Boolean.TRUE.equals(sanitize);
        }
    }
}
