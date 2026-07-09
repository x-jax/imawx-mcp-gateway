package com.imawx.mcp.gateway.service.mcpproxy.provider.nosql.elasticsearch;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.annotation.McpToolDefinition;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.support.BuiltinBackendProviderSupport;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ElasticsearchHttpApiMcpProvider extends BuiltinBackendProviderSupport implements McpProvider {

    public static final String TRANSPORT_TYPE = "ELASTICSEARCH";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ElasticsearchHttpApiMcpProvider(McpBackendService backendService,
                                           McpBackendExtensionService extensionService,
                                           BuiltinMcpDefinitionService definitionService) {
        super(backendService, extensionService, definitionService);
    }

    @Override
    public String serverType() {
        return TRANSPORT_TYPE;
    }

    @Override
    protected String transportType() {
        return TRANSPORT_TYPE;
    }

    @Override
    protected String providerLabel() {
        return "Elasticsearch";
    }

    @Override
    public McpProviderCallResult callTool(McpProviderCallRequest request) {
        McpBackendDO backend = backend(request.serverId());
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        Object data = switch (request.toolName()) {
            case "es_list_indices" -> listIndices(backend, args);
            case "es_search" -> search(backend, args);
            case "es_count" -> count(backend, args);
            case "es_get_doc" -> getDoc(backend, args);
            case "es_get_mapping" -> getMapping(backend, args);
            case "es_list_aliases" -> listAliases(backend, args);
            case "es_cluster_health" -> clusterHealth(backend);
            case "es_index_doc" -> indexDoc(backend, args);
            case "es_update_doc" -> updateDoc(backend, args);
            case "es_delete_doc" -> deleteDoc(backend, args);
            default -> throw new BizException(BizErrorCode.NOT_FOUND, "Elasticsearch tool 不存在: " + request.toolName());
        };
        return textResult(data);
    }

    @McpToolDefinition(
            name = "es_list_indices",
            description = "列出 Elasticsearch index",
            inputSchema = """
                    {"type":"object","properties":{"pattern":{"type":"string"}}}
                    """)
    private Object listIndices(McpBackendDO backend, Map<String, Object> args) {
        String pattern = firstNonBlank(str(args, "pattern"), "*");
        String prefix = str(config(backend), "indexPrefix");
        if (prefix != null && !prefix.isBlank() && "*".equals(pattern)) {
            pattern = prefix + "*";
        }
        if (prefix != null && !prefix.isBlank() && !pattern.startsWith(prefix)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "index pattern 不在前缀范围内: " + pattern);
        }
        return request(backend, "GET", "/_cat/indices/" + encodePath(pattern) + "?format=json", null);
    }

    @McpToolDefinition(
            name = "es_search",
            description = "查询 Elasticsearch 文档",
            inputSchema = """
                    {"type":"object","properties":{"index":{"type":"string"},"query":{"type":"object"},"size":{"type":"integer","minimum":1,"maximum":200}},"required":["index"]}
                    """)
    private Object search(McpBackendDO backend, Map<String, Object> args) {
        String index = scopedIndex(backend, required(args, "index"));
        int size = Math.max(1, Math.min(intValue(args.get("size"), 50), 200));
        Map<String, Object> body = new LinkedHashMap<>();
        Object query = args.get("query");
        body.put("query", query == null ? Map.of("match_all", Map.of()) : query);
        body.put("size", size);
        return request(backend, "POST", "/" + encodePath(index) + "/_search", body);
    }

    @McpToolDefinition(
            name = "es_count",
            description = "统计 Elasticsearch 文档数量",
            inputSchema = """
                    {"type":"object","properties":{"index":{"type":"string"},"query":{"type":"object"}},"required":["index"]}
                    """)
    private Object count(McpBackendDO backend, Map<String, Object> args) {
        String index = scopedIndex(backend, required(args, "index"));
        Object query = args.get("query");
        Map<String, Object> body = Map.of("query", query == null ? Map.of("match_all", Map.of()) : query);
        return request(backend, "POST", "/" + encodePath(index) + "/_count", body);
    }

    @McpToolDefinition(
            name = "es_get_doc",
            description = "按 id 读取 Elasticsearch 文档",
            inputSchema = """
                    {"type":"object","properties":{"index":{"type":"string"},"id":{"type":"string"}},"required":["index","id"]}
                    """)
    private Object getDoc(McpBackendDO backend, Map<String, Object> args) {
        String index = scopedIndex(backend, required(args, "index"));
        String id = required(args, "id");
        return request(backend, "GET", "/" + encodePath(index) + "/_doc/" + encodePath(id), null);
    }

    @McpToolDefinition(
            name = "es_get_mapping",
            description = "读取 Elasticsearch index mapping",
            inputSchema = """
                    {"type":"object","properties":{"index":{"type":"string"}},"required":["index"]}
                    """)
    private Object getMapping(McpBackendDO backend, Map<String, Object> args) {
        String index = scopedIndex(backend, required(args, "index"));
        return request(backend, "GET", "/" + encodePath(index) + "/_mapping", null);
    }

    @McpToolDefinition(
            name = "es_list_aliases",
            description = "列出 Elasticsearch alias",
            inputSchema = """
                    {"type":"object","properties":{"index":{"type":"string"}}}
                    """)
    private Object listAliases(McpBackendDO backend, Map<String, Object> args) {
        String index = str(args, "index");
        String path;
        if (index == null || index.isBlank()) {
            String prefix = str(config(backend), "indexPrefix");
            path = prefix == null || prefix.isBlank() ? "/_alias" : "/" + encodePath(prefix + "*") + "/_alias";
        } else {
            path = "/" + encodePath(scopedIndex(backend, index)) + "/_alias";
        }
        return request(backend, "GET", path, null);
    }

    @McpToolDefinition(
            name = "es_cluster_health",
            description = "读取 Elasticsearch 集群健康状态",
            inputSchema = """
                    {"type":"object","properties":{}}
                    """)
    private Object clusterHealth(McpBackendDO backend) {
        return request(backend, "GET", "/_cluster/health", null);
    }

    @McpToolDefinition(
            name = "es_index_doc",
            description = "写入 Elasticsearch 文档",
            inputSchema = """
                    {"type":"object","properties":{"index":{"type":"string"},"id":{"type":"string"},"document":{"type":"object"}},"required":["index","document"]}
                    """)
    private Object indexDoc(McpBackendDO backend, Map<String, Object> args) {
        String index = scopedIndex(backend, required(args, "index"));
        Object doc = args.get("document");
        if (!(doc instanceof Map<?, ?>)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "document 必须是 JSON 对象");
        }
        String id = str(args, "id");
        String path = "/" + encodePath(index) + "/_doc" + (id == null || id.isBlank() ? "" : "/" + encodePath(id));
        return request(backend, id == null || id.isBlank() ? "POST" : "PUT", path, doc);
    }

    @McpToolDefinition(
            name = "es_update_doc",
            description = "局部更新 Elasticsearch 文档",
            inputSchema = """
                    {"type":"object","properties":{"index":{"type":"string"},"id":{"type":"string"},"doc":{"type":"object"},"upsert":{"type":"object"}},"required":["index","id","doc"]}
                    """)
    private Object updateDoc(McpBackendDO backend, Map<String, Object> args) {
        String index = scopedIndex(backend, required(args, "index"));
        Object doc = args.get("doc");
        if (!(doc instanceof Map<?, ?>)) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "doc 必须是 JSON 对象");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("doc", doc);
        Object upsert = args.get("upsert");
        if (upsert instanceof Map<?, ?>) {
            body.put("upsert", upsert);
        }
        return request(backend, "POST", "/" + encodePath(index) + "/_update/" + encodePath(required(args, "id")), body);
    }

    @McpToolDefinition(
            name = "es_delete_doc",
            description = "删除 Elasticsearch 文档",
            inputSchema = """
                    {"type":"object","properties":{"index":{"type":"string"},"id":{"type":"string"}},"required":["index","id"]}
                    """)
    private Object deleteDoc(McpBackendDO backend, Map<String, Object> args) {
        String index = scopedIndex(backend, required(args, "index"));
        String id = required(args, "id");
        return request(backend, "DELETE", "/" + encodePath(index) + "/_doc/" + encodePath(id), null);
    }

    private Object request(McpBackendDO backend, String method, String path, Object body) {
        Map<String, Object> cfg = config(backend);
        String url = endpoint(backend) + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, intValue(cfg.get("timeoutSeconds"), 30))))
                .header("Accept", "application/json");
        applyAuth(backend, builder);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body), StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Object parsed = JsonUtil.fromJson(response.body(), new TypeReference<Object>() {});
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", response.statusCode());
            out.put("method", method);
            out.put("path", path);
            out.put("data", parsed == null ? response.body() : parsed);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "Elasticsearch HTTP " + response.statusCode() + ": " + response.body());
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "Elasticsearch 网络失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "Elasticsearch 调用被中断");
        }
    }

    private String endpoint(McpBackendDO backend) {
        Map<String, Object> cfg = config(backend);
        String endpoint = firstNonBlank(backend.getEndpoint(), str(cfg, "endpoint"));
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        }
        String scheme = boolValue(cfg.get("tls"), false) ? "https" : "http";
        String host = firstNonBlank(str(cfg, "host"), "127.0.0.1");
        int port = intValue(cfg.get("port"), 9200);
        return scheme + "://" + host + ":" + port;
    }

    private void applyAuth(McpBackendDO backend, HttpRequest.Builder builder) {
        Map<String, Object> cfg = config(backend);
        Map<String, Object> sec = secret(backend);
        String token = firstNonBlank(str(sec, "authToken"), str(sec, "password"));
        String username = str(cfg, "username");
        if (token == null || token.isBlank()) {
            return;
        }
        if (username != null && !username.isBlank()) {
            String raw = username + ":" + token;
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        } else {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    private String scopedIndex(McpBackendDO backend, String index) {
        String prefix = str(config(backend), "indexPrefix");
        if (prefix != null && !prefix.isBlank() && !index.startsWith(prefix)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "index 不在前缀范围内: " + index);
        }
        return index;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
