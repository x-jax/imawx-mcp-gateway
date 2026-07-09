package com.imawx.mcp.gateway.service.mcpproxy.provider.nosql.mongodb;

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
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class MongoDbHttpApiMcpProvider extends BuiltinBackendProviderSupport implements McpProvider {

    public static final String TRANSPORT_TYPE = "MONGODB";

    public MongoDbHttpApiMcpProvider(McpBackendService backendService,
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
        return "MongoDB";
    }

    @Override
    public McpProviderCallResult callTool(McpProviderCallRequest request) {
        McpBackendDO backend = backend(request.serverId());
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        try (MongoClient client = open(backend)) {
            MongoDatabase db = client.getDatabase(requiredConfig(backend, "database"));
            Object data = switch (request.toolName()) {
                case "mongo_list_collections" -> listCollections(backend, db);
                case "mongo_count" -> count(backend, db, args);
                case "mongo_find" -> find(backend, db, args);
                case "mongo_find_one" -> findOne(backend, db, args);
                case "mongo_aggregate" -> aggregate(backend, db, args);
                case "mongo_distinct" -> distinct(backend, db, args);
                case "mongo_list_indexes" -> listIndexes(backend, db, args);
                case "mongo_collection_stats" -> collectionStats(backend, db, args);
                case "mongo_insert_one" -> insertOne(backend, db, args);
                case "mongo_update_many" -> updateMany(backend, db, args);
                case "mongo_delete_many" -> deleteMany(backend, db, args);
                default -> throw new BizException(BizErrorCode.NOT_FOUND, "MongoDB tool 不存在: " + request.toolName());
            };
            return textResult(data);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, backend.getServerName() + " MongoDB 调用失败: " + e.getMessage());
        }
    }

    private MongoClient open(McpBackendDO backend) {
        Map<String, Object> cfg = config(backend);
        Map<String, Object> sec = secret(backend);
        String uri = shouldBuildUri(cfg, sec)
                ? buildUri(cfg, sec)
                : firstNonBlank(str(cfg, "uri"), backend.getEndpoint(), buildUri(cfg, sec));
        int timeoutMs = Math.max(1, intValue(cfg.get("timeoutSeconds"), 30)) * 1000;
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToSocketSettings(s -> s.connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS))
                .build();
        return MongoClients.create(settings);
    }

    private String buildUri(Map<String, Object> cfg, Map<String, Object> sec) {
        String host = firstNonBlank(str(cfg, "host"), "127.0.0.1");
        int port = intValue(cfg.get("port"), 27017);
        String database = firstNonBlank(str(cfg, "database"), "admin");
        String username = str(cfg, "username");
        String password = firstNonBlank(str(sec, "authToken"), str(sec, "password"));
        String authDatabase = firstNonBlank(str(cfg, "authDatabase"), database);
        String auth = "";
        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            auth = encode(username) + ":" + encode(password) + "@";
        }
        return "mongodb://" + auth + host + ":" + port + "/" + database + "?authSource=" + encode(authDatabase);
    }

    private boolean shouldBuildUri(Map<String, Object> cfg, Map<String, Object> sec) {
        return firstNonBlank(str(cfg, "host"), str(cfg, "username"), str(sec, "authToken"), str(sec, "password")) != null;
    }

    @McpToolDefinition(
            name = "mongo_list_collections",
            description = "列出 MongoDB collection",
            inputSchema = """
                    {"type":"object","properties":{}}
                    """)
    private Map<String, Object> listCollections(McpBackendDO backend, MongoDatabase db) {
        String prefix = str(config(backend), "collectionPrefix");
        List<String> collections = new ArrayList<>();
        for (String name : db.listCollectionNames()) {
            if (prefix == null || prefix.isBlank() || name.startsWith(prefix)) {
                collections.add(name);
            }
        }
        return Map.of("database", db.getName(), "collections", collections, "limitedByPrefix", prefix != null && !prefix.isBlank());
    }

    @McpToolDefinition(
            name = "mongo_count",
            description = "统计 collection 文档数量",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"},"filter":{"type":"object"}},"required":["collection"]}
                    """)
    private Map<String, Object> count(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        long count = collection.countDocuments(document(args.get("filter")));
        return Map.of("collection", collection.getNamespace().getCollectionName(), "count", count);
    }

    @McpToolDefinition(
            name = "mongo_find",
            description = "查询 MongoDB 文档",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"},"filter":{"type":"object"},"projection":{"type":"object"},"limit":{"type":"integer","minimum":1,"maximum":200}},"required":["collection"]}
                    """)
    private Map<String, Object> find(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        int limit = Math.max(1, Math.min(intValue(args.get("limit"), 50), 200));
        Document projection = document(args.get("projection"));
        List<Map<String, Object>> docs = new ArrayList<>();
        var iterable = collection.find(document(args.get("filter"))).limit(limit);
        if (!projection.isEmpty()) {
            iterable.projection(projection);
        }
        for (Document doc : iterable) {
            docs.add(JsonUtil.fromJson(doc.toJson(), new TypeReference<Map<String, Object>>() {}));
        }
        return Map.of("collection", collection.getNamespace().getCollectionName(), "limit", limit, "documents", docs);
    }

    @McpToolDefinition(
            name = "mongo_find_one",
            description = "查询 MongoDB 单条文档",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"},"filter":{"type":"object"},"projection":{"type":"object"}},"required":["collection"]}
                    """)
    private Map<String, Object> findOne(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        var iterable = collection.find(document(args.get("filter"))).limit(1);
        Document projection = document(args.get("projection"));
        if (!projection.isEmpty()) {
            iterable.projection(projection);
        }
        Document doc = iterable.first();
        return Map.of(
                "collection", collection.getNamespace().getCollectionName(),
                "document", doc == null ? Map.of() : jsonMap(doc));
    }

    @McpToolDefinition(
            name = "mongo_aggregate",
            description = "执行 MongoDB 聚合管道",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"},"pipeline":{"type":"array","items":{"type":"object"}},"limit":{"type":"integer","minimum":1,"maximum":200}},"required":["collection","pipeline"]}
                    """)
    private Map<String, Object> aggregate(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        List<Document> pipeline = documents(args.get("pipeline"));
        if (pipeline.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "pipeline 不能为空");
        }
        int limit = Math.max(1, Math.min(intValue(args.get("limit"), 50), 200));
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            docs.add(jsonMap(doc));
            if (docs.size() >= limit) break;
        }
        return Map.of("collection", collection.getNamespace().getCollectionName(), "limit", limit, "documents", docs);
    }

    @McpToolDefinition(
            name = "mongo_distinct",
            description = "查询 MongoDB 字段去重值",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"},"field":{"type":"string"},"filter":{"type":"object"},"limit":{"type":"integer","minimum":1,"maximum":200}},"required":["collection","field"]}
                    """)
    private Map<String, Object> distinct(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        String field = required(args, "field");
        int limit = Math.max(1, Math.min(intValue(args.get("limit"), 100), 200));
        List<Document> pipeline = new ArrayList<>();
        Document filter = document(args.get("filter"));
        if (!filter.isEmpty()) {
            pipeline.add(new Document("$match", filter));
        }
        pipeline.add(new Document("$group", new Document("_id", "$" + field)));
        pipeline.add(new Document("$limit", limit));
        List<Object> values = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            values.add(jsonValue(doc.get("_id")));
        }
        return Map.of("collection", collection.getNamespace().getCollectionName(), "field", field, "values", values);
    }

    @McpToolDefinition(
            name = "mongo_list_indexes",
            description = "列出 MongoDB collection 索引",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"}},"required":["collection"]}
                    """)
    private Map<String, Object> listIndexes(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        List<Map<String, Object>> indexes = new ArrayList<>();
        for (Document doc : collection.listIndexes()) {
            indexes.add(jsonMap(doc));
        }
        return Map.of("collection", collection.getNamespace().getCollectionName(), "indexes", indexes);
    }

    @McpToolDefinition(
            name = "mongo_collection_stats",
            description = "读取 MongoDB collection 统计信息",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"}},"required":["collection"]}
                    """)
    private Map<String, Object> collectionStats(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        String collection = collection(backend, db, args).getNamespace().getCollectionName();
        Document stats = db.runCommand(new Document("collStats", collection));
        return Map.of("collection", collection, "stats", jsonMap(stats));
    }

    @McpToolDefinition(
            name = "mongo_insert_one",
            description = "插入单条 MongoDB 文档",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"},"document":{"type":"object"}},"required":["collection","document"]}
                    """)
    private Map<String, Object> insertOne(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        Document doc = document(args.get("document"));
        if (doc.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "document 不能为空");
        }
        collection.insertOne(doc);
        return Map.of("collection", collection.getNamespace().getCollectionName(), "insertedId", String.valueOf(doc.get("_id")));
    }

    @McpToolDefinition(
            name = "mongo_update_many",
            description = "批量更新 MongoDB 文档",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"},"filter":{"type":"object"},"update":{"type":"object"}},"required":["collection","filter","update"]}
                    """)
    private Map<String, Object> updateMany(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        Document filter = document(args.get("filter"));
        Document update = document(args.get("update"));
        if (filter.isEmpty() || update.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "filter/update 不能为空");
        }
        var result = collection.updateMany(filter, update);
        return Map.of("collection", collection.getNamespace().getCollectionName(),
                "matched", result.getMatchedCount(), "modified", result.getModifiedCount());
    }

    @McpToolDefinition(
            name = "mongo_delete_many",
            description = "批量删除 MongoDB 文档",
            inputSchema = """
                    {"type":"object","properties":{"collection":{"type":"string"},"filter":{"type":"object"}},"required":["collection","filter"]}
                    """)
    private Map<String, Object> deleteMany(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        MongoCollection<Document> collection = collection(backend, db, args);
        Document filter = document(args.get("filter"));
        if (filter.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "filter 不能为空，禁止无条件删除");
        }
        var result = collection.deleteMany(filter);
        return Map.of("collection", collection.getNamespace().getCollectionName(), "deleted", result.getDeletedCount());
    }

    private MongoCollection<Document> collection(McpBackendDO backend, MongoDatabase db, Map<String, Object> args) {
        String collection = required(args, "collection");
        String prefix = str(config(backend), "collectionPrefix");
        if (prefix != null && !prefix.isBlank() && !collection.startsWith(prefix)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "collection 不在前缀范围内: " + collection);
        }
        return db.getCollection(collection);
    }

    @SuppressWarnings("unchecked")
    private static Document document(Object value) {
        if (value == null) return new Document();
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        if (value instanceof String s && !s.isBlank()) return Document.parse(s);
        return new Document();
    }

    private static List<Document> documents(Object value) {
        List<Document> out = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Document doc = document(item);
                if (!doc.isEmpty()) out.add(doc);
            }
        } else {
            Document doc = document(value);
            if (!doc.isEmpty()) out.add(doc);
        }
        return out;
    }

    private static Map<String, Object> jsonMap(Document document) {
        return JsonUtil.fromJson(document.toJson(), new TypeReference<Map<String, Object>>() {});
    }

    private static Object jsonValue(Object value) {
        if (value instanceof Document document) {
            return jsonMap(document);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(jsonValue(item));
            }
            return out;
        }
        return value;
    }

    private String requiredConfig(McpBackendDO backend, String key) {
        String value = str(config(backend), key);
        if (value == null || value.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "MongoDB " + key + " 未配置");
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
