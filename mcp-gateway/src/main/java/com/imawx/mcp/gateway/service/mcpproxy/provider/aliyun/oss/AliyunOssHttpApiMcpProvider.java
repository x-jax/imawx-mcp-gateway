package com.imawx.mcp.gateway.service.mcpproxy.provider.aliyun.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.Bucket;
import com.aliyun.oss.model.CopyObjectResult;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.config.McpGatewayProperties;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.annotation.McpToolDefinition;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderServer;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderTool;
import com.imawx.mcp.gateway.service.mcpproxy.provider.aliyun.AliyunProviderSupport;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AliyunOssHttpApiMcpProvider extends AliyunProviderSupport implements McpProvider {

    private static final String DEFAULT_ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com";
    private static final String TRANSPORT_TYPE = "ALIYUN_OSS";
    private final BuiltinMcpDefinitionService definitionService;
    private final McpGatewayProperties properties;

    public AliyunOssHttpApiMcpProvider(McpBackendService backendService,
                                       McpBackendExtensionService extensionService,
                                       BuiltinMcpDefinitionService definitionService,
                                       McpGatewayProperties properties) {
        super(backendService, extensionService);
        this.definitionService = definitionService;
        this.properties = properties;
    }

    @Override
    public String serverType() {
        return "ALIYUN_OSS";
    }

    @Override
    public List<McpProviderServer> listEnabledServers() {
        return listEnabledBackends(TRANSPORT_TYPE).stream()
                .map(b -> new McpProviderServer(serverType(), b.getBackendId(), b.getServerName(), true))
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
        McpBackendDO backend = backend(request.serverId());
        requireCredentials(backend);
        return switch (request.toolName()) {
            case "oss_list_buckets" -> textResult(listBuckets(backend));
            case "oss_list_objects" -> textResult(listObjects(backend, request.arguments()));
            case "oss_get_object_metadata" -> textResult(getObjectMetadata(backend, request.arguments()));
            case "oss_get_object_text" -> textResult(getObjectText(backend, request.arguments()));
            case "oss_put_object_text" -> textResult(putObjectText(backend, request.arguments()));
            case "oss_put_object_file" -> textResult(putObjectFile(backend, request.arguments()));
            case "oss_copy_object" -> textResult(copyObject(backend, request.arguments()));
            case "oss_presign_get_object" -> textResult(presignGetObject(backend, request.arguments()));
            case "oss_delete_object" -> textResult(deleteObject(backend, request.arguments()));
            default -> throw new BizException(BizErrorCode.NOT_FOUND, "阿里云 OSS tool 不存在: " + request.toolName());
        };
    }

    @McpToolDefinition(
            name = "oss_list_buckets",
            description = "列出允许管理的 OSS bucket",
            inputSchema = """
                    {"type":"object","properties":{"prefix":{"type":"string"},"region":{"type":"string"}}}
                    """)
    private Map<String, Object> listBuckets(McpBackendDO backend) {
        Set<String> allowed = allowedBuckets(backend);
        try (OssClientBox box = client(backend)) {
            List<Bucket> buckets = box.oss().listBuckets().stream()
                    .filter(b -> allowed.isEmpty() || allowed.contains(b.getName()))
                    .toList();
            return Map.of("buckets", buckets.stream().map(this::bucketMap).toList(), "limitedByWhitelist", !allowed.isEmpty());
        }
    }

    @McpToolDefinition(
            name = "oss_list_objects",
            description = "列出指定 bucket 下的对象",
            inputSchema = """
                    {"type":"object","properties":{"bucket":{"type":"string"},"prefix":{"type":"string"},"marker":{"type":"string"},"maxKeys":{"type":"integer","minimum":1,"maximum":1000}},"required":["bucket"]}
                    """)
    private Map<String, Object> listObjects(McpBackendDO backend, Map<String, Object> args) {
        String bucket = resolveBucket(backend, args);
        ListObjectsRequest req = new ListObjectsRequest(bucket);
        String prefix = str(args, "prefix");
        String marker = str(args, "marker");
        if (!blank(prefix)) req.setPrefix(prefix);
        if (!blank(marker)) req.setMarker(marker);
        req.setMaxKeys(intArg(args, "maxKeys", 100, 1, 1000));
        try (OssClientBox box = client(backend)) {
            ObjectListing listing = box.oss().listObjects(req);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bucket", bucket);
            out.put("prefix", listing.getPrefix());
            out.put("marker", listing.getMarker());
            out.put("nextMarker", listing.getNextMarker());
            out.put("truncated", listing.isTruncated());
            out.put("objects", listing.getObjectSummaries().stream().map(this::objectMap).toList());
            return out;
        }
    }

    @McpToolDefinition(
            name = "oss_get_object_metadata",
            description = "读取 OSS 对象元数据",
            inputSchema = """
                    {"type":"object","properties":{"bucket":{"type":"string"},"key":{"type":"string"}},"required":["bucket","key"]}
                    """)
    private Map<String, Object> getObjectMetadata(McpBackendDO backend, Map<String, Object> args) {
        String bucket = resolveBucket(backend, args);
        String key = requireString(args, "key");
        try (OssClientBox box = client(backend)) {
            ObjectMetadata md = box.oss().getObjectMetadata(bucket, key);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bucket", bucket);
            out.put("key", key);
            out.put("contentLength", md.getContentLength());
            out.put("contentType", md.getContentType());
            out.put("etag", md.getETag());
            out.put("lastModified", md.getLastModified());
            out.put("storageClass", md.getObjectStorageClass() == null ? null : md.getObjectStorageClass().toString());
            out.put("userMetadata", md.getUserMetadata());
            return out;
        }
    }

    @McpToolDefinition(
            name = "oss_get_object_text",
            description = "读取 OSS 文本对象内容",
            inputSchema = """
                    {"type":"object","properties":{"bucket":{"type":"string"},"key":{"type":"string"},"maxBytes":{"type":"integer","minimum":1,"maximum":1048576}},"required":["bucket","key"]}
                    """)
    private Map<String, Object> getObjectText(McpBackendDO backend, Map<String, Object> args) {
        String bucket = resolveBucket(backend, args);
        String key = requireString(args, "key");
        int maxBytes = intArg(args, "maxBytes", 65536, 1, 1024 * 1024);
        try (OssClientBox box = client(backend);
             OSSObject object = box.oss().getObject(bucket, key)) {
            byte[] bytes = object.getObjectContent().readNBytes(maxBytes + 1);
            boolean truncated = bytes.length > maxBytes;
            if (truncated) {
                bytes = java.util.Arrays.copyOf(bytes, maxBytes);
            }
            return Map.of(
                    "bucket", bucket,
                    "key", key,
                    "maxBytes", maxBytes,
                    "truncated", truncated,
                    "content", new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "读取 OSS 对象失败: " + e.getMessage());
        }
    }

    @McpToolDefinition(
            name = "oss_put_object_text",
            description = "写入文本对象到 OSS",
            inputSchema = """
                    {"type":"object","properties":{"bucket":{"type":"string"},"key":{"type":"string"},"content":{"type":"string"},"contentType":{"type":"string"}},"required":["bucket","key","content"]}
                    """)
    private Map<String, Object> putObjectText(McpBackendDO backend, Map<String, Object> args) {
        String bucket = resolveBucket(backend, args);
        String key = requireString(args, "key");
        String content = requireString(args, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(firstNonBlank(str(args, "contentType"), "text/plain; charset=utf-8"));
        try (OssClientBox box = client(backend)) {
            PutObjectResult r = box.oss().putObject(bucket, key, new ByteArrayInputStream(bytes), metadata);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bucket", bucket);
            out.put("key", key);
            out.put("etag", r.getETag());
            out.put("versionId", r.getVersionId());
            return out;
        }
    }

    @McpToolDefinition(
            name = "oss_put_object_file",
            description = "上传 Base64 编码文件到 OSS",
            inputSchema = """
                    {"type":"object","properties":{"bucket":{"type":"string","description":"目标 bucket,实例已绑定 bucket 时可不填"},"key":{"type":"string","description":"目标对象 key,例如 uploads/report.pdf"},"contentBase64":{"type":"string","description":"文件内容 Base64,可带 data: 前缀"},"contentType":{"type":"string","description":"文件 MIME 类型,例如 application/pdf"},"fileName":{"type":"string","description":"原始文件名,仅写入对象元数据"}},"required":["key","contentBase64"]}
                    """)
    private Map<String, Object> putObjectFile(McpBackendDO backend, Map<String, Object> args) {
        String bucket = resolveBucket(backend, args);
        String key = requireString(args, "key");
        int maxBytes = Math.max(1, properties.getMcp().getInvoke().getOssMaxUploadBytes());
        byte[] bytes = decodeBase64(requireString(args, "contentBase64"), maxBytes);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        String contentType = firstNonBlank(str(args, "contentType"), "application/octet-stream");
        metadata.setContentType(contentType);
        String fileName = str(args, "fileName");
        if (!blank(fileName)) {
            metadata.addUserMetadata("filename", fileName);
        }
        try (OssClientBox box = client(backend)) {
            PutObjectResult r = box.oss().putObject(bucket, key, new ByteArrayInputStream(bytes), metadata);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bucket", bucket);
            out.put("key", key);
            out.put("ossUri", ossUri(bucket, key));
            out.put("size", bytes.length);
            out.put("contentType", contentType);
            out.put("etag", r.getETag());
            out.put("versionId", r.getVersionId());
            return out;
        }
    }

    @McpToolDefinition(
            name = "oss_copy_object",
            description = "复制 OSS 对象",
            inputSchema = """
                    {"type":"object","properties":{"bucket":{"type":"string"},"key":{"type":"string"},"targetBucket":{"type":"string"},"targetKey":{"type":"string"}},"required":["bucket","key","targetKey"]}
                    """)
    private Map<String, Object> copyObject(McpBackendDO backend, Map<String, Object> args) {
        String sourceBucket = resolveBucket(backend, args);
        String sourceKey = requireString(args, "key");
        String targetBucket = firstNonBlank(str(args, "targetBucket"), sourceBucket);
        requireAllowed(targetBucket, allowedBuckets(backend), "targetBucket");
        String targetKey = requireString(args, "targetKey");
        try (OssClientBox box = client(backend)) {
            CopyObjectResult result = box.oss().copyObject(sourceBucket, sourceKey, targetBucket, targetKey);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bucket", targetBucket);
            out.put("key", targetKey);
            out.put("etag", result.getETag());
            return out;
        }
    }

    @McpToolDefinition(
            name = "oss_presign_get_object",
            description = "生成 OSS 对象临时下载 URL",
            inputSchema = """
                    {"type":"object","properties":{"bucket":{"type":"string"},"key":{"type":"string"},"expireSeconds":{"type":"integer","minimum":60,"maximum":86400}},"required":["bucket","key"]}
                    """)
    private Map<String, Object> presignGetObject(McpBackendDO backend, Map<String, Object> args) {
        String bucket = resolveBucket(backend, args);
        String key = requireString(args, "key");
        int expireSeconds = intArg(args, "expireSeconds", 900, 60, 86400);
        Date expiration = new Date(System.currentTimeMillis() + expireSeconds * 1000L);
        try (OssClientBox box = client(backend)) {
            return Map.of(
                    "bucket", bucket,
                    "key", key,
                    "expireSeconds", expireSeconds,
                    "url", box.oss().generatePresignedUrl(bucket, key, expiration).toString());
        }
    }

    @McpToolDefinition(
            name = "oss_delete_object",
            description = "删除 OSS 对象",
            inputSchema = """
                    {"type":"object","properties":{"bucket":{"type":"string"},"key":{"type":"string"}},"required":["bucket","key"]}
                    """)
    private Map<String, Object> deleteObject(McpBackendDO backend, Map<String, Object> args) {
        String bucket = resolveBucket(backend, args);
        String key = requireString(args, "key");
        try (OssClientBox box = client(backend)) {
            box.oss().deleteObject(bucket, key);
            return Map.of("bucket", bucket, "key", key, "deleted", true);
        }
    }

    private OssClientBox client(McpBackendDO backend) {
        OSS oss = new OSSClientBuilder().build(
                endpoint(backend, DEFAULT_ENDPOINT),
                accessKeyId(backend),
                accessKeySecret(backend),
                securityToken(backend));
        return new OssClientBox(oss);
    }

    private Set<String> allowedBuckets(McpBackendDO backend) {
        String bucket = firstNonBlank(str(extra(backend), "bucket"), str(extra(backend), "bucketName"));
        return blank(bucket) ? Set.of() : Set.of(bucket);
    }

    private String resolveBucket(McpBackendDO backend, Map<String, Object> args) {
        Set<String> allowed = allowedBuckets(backend);
        String configured = allowed.stream().findFirst().orElse(null);
        String requested = str(args, "bucket");
        String bucket = firstNonBlank(configured, requested);
        requireAllowed(bucket, allowed, "bucket");
        return bucket;
    }

    private Map<String, Object> bucketMap(Bucket b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", b.getName());
        out.put("location", b.getLocation());
        out.put("region", b.getRegion());
        out.put("creationDate", b.getCreationDate());
        out.put("storageClass", b.getStorageClass() == null ? null : b.getStorageClass().toString());
        return out;
    }

    private Map<String, Object> objectMap(OSSObjectSummary s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", s.getKey());
        out.put("size", s.getSize());
        out.put("etag", s.getETag());
        out.put("lastModified", s.getLastModified());
        out.put("storageClass", s.getStorageClass());
        return out;
    }

    private static byte[] decodeBase64(String value, int maxBytes) {
        String body = value;
        int comma = body.indexOf(',');
        if (body.startsWith("data:") && comma > 0) {
            body = body.substring(comma + 1);
        }
        long estimatedBytes = body.length() * 3L / 4L;
        if (estimatedBytes > maxBytes + 2L) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "文件超过最大限制: " + maxBytes + " bytes");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(body);
            if (bytes.length > maxBytes) {
                throw new BizException(BizErrorCode.INVALID_ARGUMENT, "文件超过最大限制: " + maxBytes + " bytes");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "contentBase64 不是合法 Base64");
        }
    }

    private static String ossUri(String bucket, String key) {
        return "oss://" + bucket + "/" + key;
    }

    private record OssClientBox(OSS oss) implements AutoCloseable {
        @Override
        public void close() {
            oss.shutdown();
        }
    }

}
