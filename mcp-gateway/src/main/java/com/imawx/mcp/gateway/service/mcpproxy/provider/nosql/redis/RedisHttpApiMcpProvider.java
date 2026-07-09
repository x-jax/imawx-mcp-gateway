package com.imawx.mcp.gateway.service.mcpproxy.provider.nosql.redis;

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
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderServer;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;
import org.springframework.stereotype.Component;
import redis.clients.jedis.GeoCoordinate;
import redis.clients.jedis.args.GeoUnit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.GeoRadiusResponse;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.Tuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class RedisHttpApiMcpProvider implements McpProvider {

    private static final String TRANSPORT_TYPE = "REDIS";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private final McpBackendService backendService;
    private final McpBackendExtensionService extensionService;
    private final BuiltinMcpDefinitionService definitionService;

    public RedisHttpApiMcpProvider(McpBackendService backendService,
                                   McpBackendExtensionService extensionService,
                                   BuiltinMcpDefinitionService definitionService) {
        this.backendService = backendService;
        this.extensionService = extensionService;
        this.definitionService = definitionService;
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
        return definitionService.requireTools(TRANSPORT_TYPE);
    }

    @Override
    public McpProviderCallResult callTool(McpProviderCallRequest request) {
        McpBackendDO backend = backendService.selectByBackendId(request.serverId());
        if (backend == null || !TRANSPORT_TYPE.equalsIgnoreCase(backend.getTransportType())) {
            throw new BizException(BizErrorCode.NOT_FOUND, "Redis MCP 不存在: " + request.serverId());
        }
        String toolName = definitionService.requireTools(TRANSPORT_TYPE).stream()
                .map(McpProviderTool::name)
                .filter(name -> name.equals(request.toolName()))
                .findFirst()
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "Redis MCP tool 不存在: " + request.toolName()));

        Map<String, Object> config = extensionService.config(backend.getId());
        Map<String, Object> secret = extensionService.secret(backend.getId());
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

        guardReadOnly(config, toolName);
        try (Jedis jedis = open(backend, config, secret, args)) {
            Object data = switch (toolName) {
                case "redis_type" -> redisType(jedis, config, args);
                case "redis_ttl" -> redisTtl(jedis, config, args);
                case "redis_expire" -> redisExpire(jedis, config, args);
                case "redis_get" -> redisGet(jedis, config, args);
                case "redis_set" -> redisSet(jedis, config, args);
                case "redis_mget" -> redisMget(jedis, config, args);
                case "redis_mset" -> redisMset(jedis, config, args);
                case "redis_incr" -> redisIncr(jedis, config, args);
                case "redis_exists" -> redisExists(jedis, config, args);
                case "redis_delete" -> redisDelete(jedis, config, args);
                case "redis_scan" -> redisScan(jedis, config, args);
                case "redis_dbsize" -> redisDbsize(jedis, config, args);
                case "redis_hget" -> redisHget(jedis, config, args);
                case "redis_hset" -> redisHset(jedis, config, args);
                case "redis_hgetall" -> redisHgetAll(jedis, config, args);
                case "redis_hdel" -> redisHdel(jedis, config, args);
                case "redis_lrange" -> redisLrange(jedis, config, args);
                case "redis_llen" -> redisLlen(jedis, config, args);
                case "redis_lpush" -> redisLpush(jedis, config, args);
                case "redis_rpush" -> redisRpush(jedis, config, args);
                case "redis_lpop" -> redisLpop(jedis, config, args);
                case "redis_rpop" -> redisRpop(jedis, config, args);
                case "redis_smembers" -> redisSmembers(jedis, config, args);
                case "redis_sismember" -> redisSismember(jedis, config, args);
                case "redis_sadd" -> redisSadd(jedis, config, args);
                case "redis_srem" -> redisSrem(jedis, config, args);
                case "redis_zrange" -> redisZrange(jedis, config, args);
                case "redis_zadd" -> redisZadd(jedis, config, args);
                case "redis_zrem" -> redisZrem(jedis, config, args);
                case "redis_xadd" -> redisXadd(jedis, config, args);
                case "redis_xrange" -> redisXrange(jedis, config, args);
                case "redis_setbit" -> redisSetbit(jedis, config, args);
                case "redis_getbit" -> redisGetbit(jedis, config, args);
                case "redis_bitcount" -> redisBitcount(jedis, config, args);
                case "redis_pfadd" -> redisPfadd(jedis, config, args);
                case "redis_pfcount" -> redisPfcount(jedis, config, args);
                case "redis_geoadd" -> redisGeoadd(jedis, config, args);
                case "redis_geodist" -> redisGeodist(jedis, config, args);
                case "redis_georadius" -> redisGeoradius(jedis, config, args);
                default -> throw new BizException(BizErrorCode.NOT_FOUND, "Redis MCP tool 不存在: " + request.toolName());
            };
            return textResult(data);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, backend.getServerName() + " Redis 调用失败: " + e.getMessage());
        }
    }

    private Jedis open(McpBackendDO backend, Map<String, Object> config, Map<String, Object> secret,
                       Map<String, Object> args) {
        String host = firstNonBlank(str(config.get("host")), parseEndpointHost(backend.getEndpoint()));
        int port = intValue(config.get("port"), parseEndpointPort(backend.getEndpoint(), 6379));
        boolean tls = boolValue(config.get("tls"), backend.getEndpoint() != null && backend.getEndpoint().startsWith("rediss://"));
        int timeoutMs = Math.max(1, intValue(config.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS)) * 1000;
        if (host == null || host.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, backend.getServerName() + " Redis Host 未配置");
        }
        Jedis jedis = new Jedis(host, port, timeoutMs, tls);
        String password = firstNonBlank(str(secret.get("authToken")), str(secret.get("password")));
        String username = firstNonBlank(str(config.get("username")));
        if (password != null && username != null) {
            jedis.auth(username, password);
        } else if (password != null) {
            jedis.auth(password);
        }
        int database = resolveDatabase(config, args);
        if (database > 0) {
            jedis.select(database);
        }
        return jedis;
    }

    @McpToolDefinition(
            name = "redis_type",
            description = "查看 Redis key 类型",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string","description":"Redis key"}},"required":["key"]}
                    """)
    private Object redisType(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "type", jedis.type(key));
    }

    @McpToolDefinition(
            name = "redis_ttl",
            description = "查看 Redis key TTL",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string","description":"Redis key"}},"required":["key"]}
                    """)
    private Object redisTtl(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "ttlSeconds", jedis.ttl(key));
    }

    @McpToolDefinition(
            name = "redis_expire",
            description = "设置 Redis key 过期时间",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"ttlSeconds":{"type":"integer","minimum":1}},"required":["key","ttlSeconds"]}
                    """)
    private Object redisExpire(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        long ttl = Math.max(1, intValue(args.get("ttlSeconds"), 0));
        return Map.of("key", key, "updated", jedis.expire(key, ttl), "ttlSeconds", ttl);
    }

    @McpToolDefinition(
            name = "redis_get",
            description = "读取 Redis key",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}
                    """)
    private Object redisGet(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "value", Objects.toString(jedis.get(key), ""));
    }

    @McpToolDefinition(
            name = "redis_set",
            description = "写入 Redis key",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"},"ttlSeconds":{"type":"integer","minimum":1}},"required":["key","value"]}
                    """)
    private Object redisSet(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        String value = required(args, "value");
        int ttlSeconds = intValue(args.get("ttlSeconds"), 0);
        String result = ttlSeconds > 0 ? jedis.setex(key, ttlSeconds, value) : jedis.set(key, value);
        return Map.of("key", key, "result", result);
    }

    @McpToolDefinition(
            name = "redis_incr",
            description = "对 Redis string integer 自增",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}
                    """)
    private Object redisIncr(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "value", jedis.incr(key));
    }

    @McpToolDefinition(
            name = "redis_mget",
            description = "批量读取 Redis string key",
            inputSchema = """
                    {"type":"object","properties":{"keys":{"type":"array","items":{"type":"string"}}},"required":["keys"]}
                    """)
    private Object redisMget(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String[] keys = scopedKeys(config, args.get("keys"));
        List<String> values = jedis.mget(keys);
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) {
            out.put(keys[i], values.get(i));
        }
        return Map.of("values", out);
    }

    @McpToolDefinition(
            name = "redis_mset",
            description = "批量写入 Redis string key",
            inputSchema = """
                    {"type":"object","properties":{"values":{"type":"object","additionalProperties":{"type":"string"}}},"required":["values"]}
                    """)
    private Object redisMset(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        Map<String, String> values = stringMap(args.get("values"));
        if (values.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "values 不能为空");
        }
        List<String> kv = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            kv.add(scopedKey(config, entry.getKey()));
            kv.add(entry.getValue());
        }
        return Map.of("result", jedis.mset(kv.toArray(String[]::new)), "count", values.size());
    }

    @McpToolDefinition(
            name = "redis_exists",
            description = "判断 Redis key 是否存在",
            inputSchema = """
                    {"type":"object","properties":{"keys":{"type":"array","items":{"type":"string"}}},"required":["keys"]}
                    """)
    private Object redisExists(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String[] keys = scopedKeys(config, args.get("keys"));
        return Map.of("keys", List.of(keys), "exists", jedis.exists(keys));
    }

    @McpToolDefinition(
            name = "redis_delete",
            description = "删除 Redis key",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}
                    """)
    private Object redisDelete(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "deleted", jedis.del(key));
    }

    @McpToolDefinition(
            name = "redis_scan",
            description = "按 pattern 扫描 Redis key",
            inputSchema = """
                    {"type":"object","properties":{"pattern":{"type":"string"},"cursor":{"type":"string"},"count":{"type":"integer","minimum":1,"maximum":1000}}}
                    """)
    private Object redisScan(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String prefix = str(config.get("keyPrefix"));
        String pattern = firstNonBlank(str(args.get("pattern")), "*");
        String scopedPattern = prefix == null || prefix.isBlank() ? pattern : prefix + pattern;
        String cursor = firstNonBlank(str(args.get("cursor")), ScanParams.SCAN_POINTER_START);
        int count = Math.max(1, Math.min(intValue(args.get("count"), 100), 1000));
        ScanParams params = new ScanParams().match(scopedPattern).count(count);
        ScanResult<String> result = jedis.scan(cursor, params);
        List<String> keys = new ArrayList<>(result.getResult());
        return Map.of("cursor", result.getCursor(), "keys", keys, "count", keys.size());
    }

    @McpToolDefinition(
            name = "redis_dbsize",
            description = "统计当前 Redis DB key 数量",
            inputSchema = """
                    {"type":"object","properties":{"database":{"type":"integer","minimum":0}}}
                    """)
    private Object redisDbsize(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        return Map.of("database", resolveDatabase(config, args), "size", jedis.dbSize());
    }

    @McpToolDefinition(
            name = "redis_hget",
            description = "读取 Redis Hash 字段",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"field":{"type":"string"}},"required":["key","field"]}
                    """)
    private Object redisHget(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        String field = required(args, "field");
        return Map.of("key", key, "field", field, "value", Objects.toString(jedis.hget(key, field), ""));
    }

    @McpToolDefinition(
            name = "redis_hset",
            description = "写入 Redis Hash 字段或字段集合",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"field":{"type":"string"},"value":{"type":"string"},"fields":{"type":"object","additionalProperties":{"type":"string"}}},"required":["key"]}
                    """)
    private Object redisHset(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        Map<String, String> fields = stringMap(args.get("fields"));
        if (fields.isEmpty()) {
            fields = Map.of(required(args, "field"), required(args, "value"));
        }
        return Map.of("key", key, "updated", jedis.hset(key, fields), "fields", fields.keySet());
    }

    @McpToolDefinition(
            name = "redis_hgetall",
            description = "读取 Redis Hash 全部字段",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}
                    """)
    private Object redisHgetAll(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "fields", jedis.hgetAll(key));
    }

    @McpToolDefinition(
            name = "redis_hdel",
            description = "删除 Redis Hash 字段",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"fields":{"type":"array","items":{"type":"string"}}},"required":["key","fields"]}
                    """)
    private Object redisHdel(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        String[] fields = stringArray(args.get("fields"));
        return Map.of("key", key, "deleted", jedis.hdel(key, fields));
    }

    @McpToolDefinition(
            name = "redis_lrange",
            description = "读取 Redis List 范围",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"start":{"type":"integer","default":0},"stop":{"type":"integer","default":99}},"required":["key"]}
                    """)
    private Object redisLrange(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        long start = intValue(args.get("start"), 0);
        long stop = intValue(args.get("stop"), 99);
        return Map.of("key", key, "values", jedis.lrange(key, start, stop));
    }

    @McpToolDefinition(
            name = "redis_llen",
            description = "读取 Redis List 长度",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}
                    """)
    private Object redisLlen(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "length", jedis.llen(key));
    }

    @McpToolDefinition(
            name = "redis_lpush",
            description = "从左侧写入 Redis List",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"values":{"type":"array","items":{"type":"string"}}},"required":["key","values"]}
                    """)
    private Object redisLpush(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "length", jedis.lpush(key, stringArray(args.get("values"))));
    }

    @McpToolDefinition(
            name = "redis_rpush",
            description = "从右侧写入 Redis List",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"values":{"type":"array","items":{"type":"string"}}},"required":["key","values"]}
                    """)
    private Object redisRpush(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "length", jedis.rpush(key, stringArray(args.get("values"))));
    }

    @McpToolDefinition(
            name = "redis_lpop",
            description = "从左侧弹出 Redis List",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"count":{"type":"integer","minimum":1,"maximum":1000}},"required":["key"]}
                    """)
    private Object redisLpop(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        int count = intValue(args.get("count"), 1);
        Object values = count > 1 ? jedis.lpop(key, Math.min(count, 1000)) : jedis.lpop(key);
        return Map.of("key", key, "values", values == null ? List.of() : values);
    }

    @McpToolDefinition(
            name = "redis_rpop",
            description = "从右侧弹出 Redis List",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"count":{"type":"integer","minimum":1,"maximum":1000}},"required":["key"]}
                    """)
    private Object redisRpop(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        int count = intValue(args.get("count"), 1);
        Object values = count > 1 ? jedis.rpop(key, Math.min(count, 1000)) : jedis.rpop(key);
        return Map.of("key", key, "values", values == null ? List.of() : values);
    }

    @McpToolDefinition(
            name = "redis_smembers",
            description = "读取 Redis Set 成员",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}
                    """)
    private Object redisSmembers(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        Set<String> members = jedis.smembers(key);
        return Map.of("key", key, "members", members);
    }

    @McpToolDefinition(
            name = "redis_sismember",
            description = "判断 Redis Set 成员是否存在",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"member":{"type":"string"}},"required":["key","member"]}
                    """)
    private Object redisSismember(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        String member = required(args, "member");
        return Map.of("key", key, "member", member, "exists", jedis.sismember(key, member));
    }

    @McpToolDefinition(
            name = "redis_sadd",
            description = "写入 Redis Set 成员",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"members":{"type":"array","items":{"type":"string"}}},"required":["key","members"]}
                    """)
    private Object redisSadd(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "added", jedis.sadd(key, stringArray(args.get("members"))));
    }

    @McpToolDefinition(
            name = "redis_srem",
            description = "删除 Redis Set 成员",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"members":{"type":"array","items":{"type":"string"}}},"required":["key","members"]}
                    """)
    private Object redisSrem(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "removed", jedis.srem(key, stringArray(args.get("members"))));
    }

    @McpToolDefinition(
            name = "redis_zrange",
            description = "读取 Redis ZSet 范围",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"start":{"type":"integer","default":0},"stop":{"type":"integer","default":99},"withScores":{"type":"boolean","default":true}},"required":["key"]}
                    """)
    private Object redisZrange(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        long start = intValue(args.get("start"), 0);
        long stop = intValue(args.get("stop"), 99);
        if (boolValue(args.get("withScores"), true)) {
            return Map.of("key", key, "items", tuples(jedis.zrangeWithScores(key, start, stop)));
        }
        return Map.of("key", key, "members", jedis.zrange(key, start, stop));
    }

    @McpToolDefinition(
            name = "redis_zadd",
            description = "写入 Redis ZSet 成员",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"member":{"type":"string"},"score":{"type":"number"},"items":{"type":"object","additionalProperties":{"type":"number"}}},"required":["key"]}
                    """)
    private Object redisZadd(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        Map<String, Double> items = doubleMap(args.get("items"));
        if (items.isEmpty()) {
            items = Map.of(required(args, "member"), doubleValue(args.get("score"), 0D));
        }
        return Map.of("key", key, "added", jedis.zadd(key, items), "members", items.keySet());
    }

    @McpToolDefinition(
            name = "redis_zrem",
            description = "删除 Redis ZSet 成员",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"members":{"type":"array","items":{"type":"string"}}},"required":["key","members"]}
                    """)
    private Object redisZrem(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "removed", jedis.zrem(key, stringArray(args.get("members"))));
    }

    @McpToolDefinition(
            name = "redis_xadd",
            description = "写入 Redis Stream 消息",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"fields":{"type":"object","additionalProperties":{"type":"string"}}},"required":["key","fields"]}
                    """)
    private Object redisXadd(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        Map<String, String> fields = stringMap(args.get("fields"));
        if (fields.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "fields 不能为空");
        }
        return Map.of("key", key, "id", jedis.xadd(key, StreamEntryID.NEW_ENTRY, fields).toString());
    }

    @McpToolDefinition(
            name = "redis_xrange",
            description = "读取 Redis Stream 消息范围",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"start":{"type":"string","default":"-"},"end":{"type":"string","default":"+"},"count":{"type":"integer","minimum":1,"maximum":1000}},"required":["key"]}
                    """)
    private Object redisXrange(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        String start = firstNonBlank(str(args.get("start")), "-");
        String end = firstNonBlank(str(args.get("end")), "+");
        int count = Math.max(1, Math.min(intValue(args.get("count"), 100), 1000));
        List<StreamEntry> entries = jedis.xrange(key, start, end, count);
        List<Map<String, Object>> out = new ArrayList<>();
        for (StreamEntry entry : entries) {
            out.add(Map.of("id", entry.getID().toString(), "fields", entry.getFields()));
        }
        return Map.of("key", key, "entries", out, "count", out.size());
    }

    @McpToolDefinition(
            name = "redis_setbit",
            description = "写入 Redis Bitmap bit",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"offset":{"type":"integer","minimum":0},"value":{"type":"boolean"}},"required":["key","offset","value"]}
                    """)
    private Object redisSetbit(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        long offset = longValue(args.get("offset"), 0L);
        boolean value = boolValue(args.get("value"), false);
        return Map.of("key", key, "offset", offset, "previous", jedis.setbit(key, offset, value), "value", value);
    }

    @McpToolDefinition(
            name = "redis_getbit",
            description = "读取 Redis Bitmap bit",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"offset":{"type":"integer","minimum":0}},"required":["key","offset"]}
                    """)
    private Object redisGetbit(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        long offset = longValue(args.get("offset"), 0L);
        return Map.of("key", key, "offset", offset, "value", jedis.getbit(key, offset));
    }

    @McpToolDefinition(
            name = "redis_bitcount",
            description = "统计 Redis Bitmap 置位数量",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"start":{"type":"integer"},"end":{"type":"integer"}},"required":["key"]}
                    """)
    private Object redisBitcount(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        if (args.containsKey("start") && args.containsKey("end")) {
            return Map.of("key", key, "count", jedis.bitcount(key, longValue(args.get("start"), 0L), longValue(args.get("end"), -1L)));
        }
        return Map.of("key", key, "count", jedis.bitcount(key));
    }

    @McpToolDefinition(
            name = "redis_pfadd",
            description = "写入 Redis HyperLogLog 元素",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"elements":{"type":"array","items":{"type":"string"}}},"required":["key","elements"]}
                    """)
    private Object redisPfadd(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        return Map.of("key", key, "updated", jedis.pfadd(key, stringArray(args.get("elements"))));
    }

    @McpToolDefinition(
            name = "redis_pfcount",
            description = "统计 Redis HyperLogLog 基数",
            inputSchema = """
                    {"type":"object","properties":{"keys":{"type":"array","items":{"type":"string"}}},"required":["keys"]}
                    """)
    private Object redisPfcount(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String[] keys = scopedKeys(config, args.get("keys"));
        return Map.of("keys", List.of(keys), "count", jedis.pfcount(keys));
    }

    @McpToolDefinition(
            name = "redis_geoadd",
            description = "写入 Redis Geo 坐标",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"member":{"type":"string"},"longitude":{"type":"number"},"latitude":{"type":"number"}},"required":["key","member","longitude","latitude"]}
                    """)
    private Object redisGeoadd(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        String member = required(args, "member");
        double longitude = doubleValue(args.get("longitude"), 0D);
        double latitude = doubleValue(args.get("latitude"), 0D);
        return Map.of("key", key, "added", jedis.geoadd(key, longitude, latitude, member), "member", member);
    }

    @McpToolDefinition(
            name = "redis_geodist",
            description = "计算 Redis Geo 两成员距离",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"member1":{"type":"string"},"member2":{"type":"string"},"unit":{"type":"string","enum":["M","KM","MI","FT"]}},"required":["key","member1","member2"]}
                    """)
    private Object redisGeodist(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        GeoUnit unit = geoUnit(args.get("unit"));
        Double distance = jedis.geodist(key, required(args, "member1"), required(args, "member2"), unit);
        return Map.of("key", key, "distance", distance == null ? 0D : distance, "unit", unit.name());
    }

    @McpToolDefinition(
            name = "redis_georadius",
            description = "按坐标半径查询 Redis Geo 成员",
            inputSchema = """
                    {"type":"object","properties":{"key":{"type":"string"},"longitude":{"type":"number"},"latitude":{"type":"number"},"radius":{"type":"number"},"unit":{"type":"string","enum":["M","KM","MI","FT"]}},"required":["key","longitude","latitude","radius"]}
                    """)
    private Object redisGeoradius(Jedis jedis, Map<String, Object> config, Map<String, Object> args) {
        String key = scopedKey(config, required(args, "key"));
        double longitude = doubleValue(args.get("longitude"), 0D);
        double latitude = doubleValue(args.get("latitude"), 0D);
        double radius = doubleValue(args.get("radius"), 1D);
        GeoUnit unit = geoUnit(args.get("unit"));
        List<GeoRadiusResponse> responses = jedis.georadius(key, longitude, latitude, radius, unit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (GeoRadiusResponse response : responses) {
            out.add(Map.of("member", response.getMemberByString()));
        }
        return Map.of("key", key, "members", out, "count", out.size(), "unit", unit.name());
    }

    private static String scopedKey(Map<String, Object> config, String key) {
        String prefix = str(config.get("keyPrefix"));
        if (prefix == null || prefix.isBlank()) {
            return key;
        }
        if (!key.startsWith(prefix)) {
            return prefix + key;
        }
        return key;
    }

    private static String[] scopedKeys(Map<String, Object> config, Object value) {
        String[] keys = stringArray(value);
        for (int i = 0; i < keys.length; i++) {
            keys[i] = scopedKey(config, keys[i]);
        }
        return keys;
    }

    private static String required(Map<String, Object> args, String key) {
        String value = str(args.get(key));
        if (value == null || value.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, key + " 不能为空");
        }
        return value;
    }

    private static McpProviderCallResult textResult(Object data) {
        String text = data instanceof String s ? s : JsonUtil.toJson(data);
        return new McpProviderCallResult(
                List.of(Map.of("type", "text", "text", text)),
                false,
                Map.of()
        );
    }

    private static Map<String, Object> providerConfig(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(config, out, "database");
        copyIfPresent(config, out, "databases");
        copyIfPresent(config, out, "keyPrefix");
        copyIfPresent(config, out, "host");
        copyIfPresent(config, out, "port");
        copyIfPresent(config, out, "username");
        copyIfPresent(config, out, "tls");
        copyIfPresent(config, out, "namespace");
        copyIfPresent(config, out, "readOnly");
        copyIfPresent(config, out, "allowedCommands");
        return out;
    }

    private static void guardReadOnly(Map<String, Object> config, String toolName) {
        if (!boolValue(config.get("readOnly"), false)) {
            return;
        }
        if (Set.of(
                "redis_expire",
                "redis_set",
                "redis_mset",
                "redis_incr",
                "redis_delete",
                "redis_hset",
                "redis_hdel",
                "redis_lpush",
                "redis_rpush",
                "redis_lpop",
                "redis_rpop",
                "redis_sadd",
                "redis_srem",
                "redis_zadd",
                "redis_zrem",
                "redis_xadd",
                "redis_setbit",
                "redis_pfadd",
                "redis_geoadd"
        ).contains(toolName)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "Redis MCP 已开启只读模式，禁止执行写操作: " + toolName);
        }
    }

    private static int resolveDatabase(Map<String, Object> config, Map<String, Object> args) {
        Integer requested = optionalInt(args == null ? null : args.get("database"));
        Integer configured = optionalInt(config.get("database"));
        Set<Integer> allowed = intSet(config.get("databases"));
        int database = requested != null ? requested : configured != null ? configured : allowed.stream().findFirst().orElse(0);
        if (database < 0 || database > 15) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "Redis database 必须在 0-15 范围内");
        }
        if (!allowed.isEmpty() && !allowed.contains(database)) {
            throw new BizException(BizErrorCode.FORBIDDEN, "Redis database 不在允许范围内: " + database);
        }
        return database;
    }

    private static Integer optionalInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Set<Integer> intSet(Object value) {
        if (value == null) return Set.of();
        List<Integer> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Integer parsed = optionalInt(item);
                if (parsed != null) values.add(parsed);
            }
        } else {
            String text = str(value);
            if (text != null && !text.isBlank()) {
                Arrays.stream(text.split("[,\\r\\n]+"))
                        .map(RedisHttpApiMcpProvider::optionalInt)
                        .filter(Objects::nonNull)
                        .forEach(values::add);
            }
        }
        return Set.copyOf(values);
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key)) {
            dst.put(key, src.get(key));
        }
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

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
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

    private static GeoUnit geoUnit(Object value) {
        String unit = firstNonBlank(str(value), "M");
        try {
            return GeoUnit.valueOf(unit.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "Geo unit 只支持 M/KM/MI/FT");
        }
    }

    private static String parseEndpointHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return null;
        try {
            return java.net.URI.create(endpoint.trim()).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int parseEndpointPort(String endpoint, int fallback) {
        if (endpoint == null || endpoint.isBlank()) return fallback;
        try {
            int port = java.net.URI.create(endpoint.trim()).getPort();
            return port > 0 ? port : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String[] stringArray(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !Objects.toString(item).isBlank()) {
                    values.add(Objects.toString(item));
                }
            }
        } else if (value instanceof String s) {
            values.addAll(Arrays.stream(s.split("[,\\r\\n]+"))
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .toList());
        }
        if (values.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "数组参数不能为空");
        }
        return values.toArray(String[]::new);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(Objects.toString(entry.getKey()), Objects.toString(entry.getValue()));
                }
            }
        }
        return out;
    }

    private static Map<String, Double> doubleMap(Object value) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(Objects.toString(entry.getKey()), doubleValue(entry.getValue(), 0D));
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> tuples(List<Tuple> tuples) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tuple tuple : tuples) {
            out.add(Map.of("member", tuple.getElement(), "score", tuple.getScore()));
        }
        return out;
    }

}
