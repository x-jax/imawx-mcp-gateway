package com.imawx.mcp.gateway.service.mcpproxy.provider.aliyun.dns;

import com.aliyun.alidns20150109.Client;
import com.aliyun.alidns20150109.models.AddDomainRecordRequest;
import com.aliyun.alidns20150109.models.AddDomainRecordResponse;
import com.aliyun.alidns20150109.models.DeleteDomainRecordRequest;
import com.aliyun.alidns20150109.models.DeleteDomainRecordResponse;
import com.aliyun.alidns20150109.models.DescribeDomainRecordsRequest;
import com.aliyun.alidns20150109.models.DescribeDomainRecordsResponse;
import com.aliyun.alidns20150109.models.DescribeDomainRecordsResponseBody;
import com.aliyun.alidns20150109.models.DescribeDomainNsRequest;
import com.aliyun.alidns20150109.models.DescribeDomainNsResponseBody;
import com.aliyun.alidns20150109.models.DescribeDomainsRequest;
import com.aliyun.alidns20150109.models.DescribeDomainsResponse;
import com.aliyun.alidns20150109.models.DescribeDomainsResponseBody;
import com.aliyun.alidns20150109.models.DescribeDomainsResponseBody.DescribeDomainsResponseBodyDomainsDomain;
import com.aliyun.alidns20150109.models.UpdateDomainRecordRequest;
import com.aliyun.alidns20150109.models.UpdateDomainRecordResponse;
import com.aliyun.teaopenapi.models.Config;
import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AliyunDnsHttpApiMcpProvider extends AliyunProviderSupport implements McpProvider {

    private static final String DEFAULT_ENDPOINT = "alidns.cn-hangzhou.aliyuncs.com";
    private static final String TRANSPORT_TYPE = "ALIYUN_DNS";
    private final BuiltinMcpDefinitionService definitionService;

    public AliyunDnsHttpApiMcpProvider(McpBackendService backendService,
                                       McpBackendExtensionService extensionService,
                                       BuiltinMcpDefinitionService definitionService) {
        super(backendService, extensionService);
        this.definitionService = definitionService;
    }

    @Override
    public String serverType() {
        return "ALIYUN_DNS";
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
            case "dns_list_domains" -> textResult(listDomains(backend, request.arguments()));
            case "dns_list_records" -> textResult(listRecords(backend, request.arguments()));
            case "dns_upsert_record" -> textResult(upsertRecord(backend, request.arguments()));
            case "dns_delete_record" -> textResult(deleteRecord(backend, request.arguments()));
            default -> throw new BizException(BizErrorCode.NOT_FOUND, "阿里云域名 tool 不存在: " + request.toolName());
        };
    }

    @McpToolDefinition(
            name = "dns_list_domains",
            description = "列出阿里云账号下可管理的域名",
            inputSchema = """
                    {"type":"object","properties":{"keyword":{"type":"string"},"pageNumber":{"type":"integer","minimum":1},"pageSize":{"type":"integer","minimum":1,"maximum":100}}}
                    """)
    private Map<String, Object> listDomains(McpBackendDO backend, Map<String, Object> args) {
        Set<String> allowed = allowedDomains(backend);
        DescribeDomainsRequest req = new DescribeDomainsRequest()
                .setKeyWord(allowed.stream().findFirst().orElse(str(args, "keyword")))
                .setPageNumber(longArg(args, "pageNumber", 1, 1, 10_000))
                .setPageSize(longArg(args, "pageSize", 20, 1, 100));
        try {
            Client client = client(backend);
            DescribeDomainsResponse response = client.describeDomains(req);
            DescribeDomainsResponseBody body = response.getBody();
            List<Map<String, Object>> domains = body == null || body.getDomains() == null || body.getDomains().getDomain() == null
                    ? List.of()
                    : body.getDomains().getDomain().stream()
                    .filter(d -> allowed.isEmpty() || allowed.contains(d.getDomainName()))
                    .map(d -> toAvailableDomainMap(client, d, !allowed.isEmpty()))
                    .filter(m -> !m.isEmpty())
                    .toList();
            return Map.of(
                    "domains", domains,
                    "totalCount", domains.size(),
                    "sourceTotalCount", body == null ? 0 : body.getTotalCount(),
                    "limitedByWhitelist", !allowed.isEmpty());
        } catch (Exception e) {
            throw remoteError(e);
        }
    }

    private Map<String, Object> toAvailableDomainMap(
            Client client, DescribeDomainsResponseBodyDomainsDomain domain, boolean limitedByConfig) {
        if (domain == null || Boolean.TRUE.equals(domain.getInstanceExpired()) || blank(domain.getDomainName())) {
            return Map.of();
        }
        DomainNsStatus nsStatus = resolveNsStatus(client, domain.getDomainName());
        if (!nsStatus.usable()) {
            return Map.of();
        }
        return toDomainMap(domain, nsStatus, limitedByConfig);
    }

    private DomainNsStatus resolveNsStatus(Client client, String domainName) {
        try {
            DescribeDomainNsResponseBody body = client.describeDomainNs(new DescribeDomainNsRequest()
                            .setDomainName(domainName))
                    .getBody();
            boolean allAliDns = body != null && Boolean.TRUE.equals(body.getAllAliDns());
            return new DomainNsStatus(
                    allAliDns,
                    body == null ? null : body.getIncludeAliDns(),
                    body == null ? null : body.getDetectFailedReasonCode(),
                    body == null || body.getDnsServers() == null ? List.of() : body.getDnsServers().getDnsServer(),
                    body == null || body.getExpectDnsServers() == null ? List.of() : body.getExpectDnsServers().getExpectDnsServer());
        } catch (Exception e) {
            return new DomainNsStatus(false, null, e.getMessage(), List.of(), List.of());
        }
    }

    private Map<String, Object> toDomainMap(
            DescribeDomainsResponseBodyDomainsDomain d, DomainNsStatus nsStatus, boolean limitedByConfig) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domainName", d.getDomainName());
        out.put("domainId", d.getDomainId());
        out.put("recordCount", d.getRecordCount());
        out.put("versionCode", d.getVersionCode());
        out.put("versionName", d.getVersionName());
        out.put("groupName", d.getGroupName());
        out.put("instanceId", d.getInstanceId());
        out.put("instanceEndTime", d.getInstanceEndTime());
        out.put("instanceExpired", Boolean.TRUE.equals(d.getInstanceExpired()));
        out.put("allAliDns", nsStatus.allAliDns());
        out.put("includeAliDns", nsStatus.includeAliDns());
        out.put("dnsServers", nsStatus.dnsServers());
        out.put("expectDnsServers", nsStatus.expectDnsServers());
        if (limitedByConfig) {
            out.put("limitedByConfig", true);
        }
        return out;
    }

    private record DomainNsStatus(boolean allAliDns, Boolean includeAliDns, String detectFailedReasonCode,
                                  List<String> dnsServers, List<String> expectDnsServers) {
        boolean usable() {
            return allAliDns && detectFailedReasonCode == null;
        }
    }

    @McpToolDefinition(
            name = "dns_list_records",
            description = "查询指定域名的 DNS 解析记录",
            inputSchema = """
                    {"type":"object","properties":{"domainName":{"type":"string"},"rrKeyWord":{"type":"string"},"type":{"type":"string"},"pageNumber":{"type":"integer","minimum":1},"pageSize":{"type":"integer","minimum":1,"maximum":100}},"required":["domainName"]}
                    """)
    private Map<String, Object> listRecords(McpBackendDO backend, Map<String, Object> args) {
        String domainName = resolveDomain(backend, args);
        DescribeDomainRecordsRequest req = new DescribeDomainRecordsRequest()
                .setDomainName(domainName)
                .setRRKeyWord(str(args, "rrKeyWord"))
                .setType(upper(str(args, "type")))
                .setPageNumber(longArg(args, "pageNumber", 1, 1, 10_000))
                .setPageSize(longArg(args, "pageSize", 20, 1, 100));
        try {
            DescribeDomainRecordsResponse response = client(backend).describeDomainRecords(req);
            DescribeDomainRecordsResponseBody body = response.getBody();
            List<Map<String, Object>> records = body == null || body.getDomainRecords() == null || body.getDomainRecords().getRecord() == null
                    ? List.of()
                    : body.getDomainRecords().getRecord().stream().map(r -> {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("recordId", r.getRecordId());
                        out.put("domainName", r.getDomainName());
                        out.put("rr", r.getRR());
                        out.put("type", r.getType());
                        out.put("value", r.getValue());
                        out.put("ttl", r.getTTL());
                        out.put("line", r.getLine());
                        out.put("status", r.getStatus());
                        out.put("locked", r.getLocked());
                        return out;
                    }).toList();
            return Map.of("domainName", domainName, "records", records, "totalCount", body == null ? 0 : body.getTotalCount());
        } catch (Exception e) {
            throw remoteError(e);
        }
    }

    @McpToolDefinition(
            name = "dns_upsert_record",
            description = "新增或更新 DNS 解析记录",
            inputSchema = """
                    {"type":"object","properties":{"recordId":{"type":"string"},"domainName":{"type":"string"},"rr":{"type":"string"},"type":{"type":"string"},"value":{"type":"string"},"ttl":{"type":"integer","minimum":60},"line":{"type":"string"}},"required":["domainName","rr","type","value"]}
                    """)
    private Map<String, Object> upsertRecord(McpBackendDO backend, Map<String, Object> args) {
        String domainName = resolveDomain(backend, args);
        String rr = requireString(args, "rr");
        String type = upper(requireString(args, "type"));
        String value = requireString(args, "value");
        String line = firstNonBlank(str(args, "line"), "default");
        long ttl = longArg(args, "ttl", 600, 60, 86_400);
        String recordId = str(args, "recordId");
        try {
            if (!blank(recordId)) {
                UpdateDomainRecordResponse response = client(backend).updateDomainRecord(new UpdateDomainRecordRequest()
                        .setRecordId(recordId)
                        .setRR(rr)
                        .setType(type)
                        .setValue(value)
                        .setLine(line)
                        .setTTL(ttl));
                return Map.of("action", "update", "recordId", response.getBody().getRecordId(), "requestId", response.getBody().getRequestId());
            }
            AddDomainRecordResponse response = client(backend).addDomainRecord(new AddDomainRecordRequest()
                    .setDomainName(domainName)
                    .setRR(rr)
                    .setType(type)
                    .setValue(value)
                    .setLine(line)
                    .setTTL(ttl));
            return Map.of("action", "add", "recordId", response.getBody().getRecordId(), "requestId", response.getBody().getRequestId());
        } catch (Exception e) {
            throw remoteError(e);
        }
    }

    @McpToolDefinition(
            name = "dns_delete_record",
            description = "删除 DNS 解析记录",
            inputSchema = """
                    {"type":"object","properties":{"recordId":{"type":"string"},"domainName":{"type":"string"},"rr":{"type":"string"},"type":{"type":"string"}},"required":["recordId"]}
                    """)
    private Map<String, Object> deleteRecord(McpBackendDO backend, Map<String, Object> args) {
        String recordId = requireString(args, "recordId");
        try {
            DeleteDomainRecordResponse response = client(backend).deleteDomainRecord(new DeleteDomainRecordRequest().setRecordId(recordId));
            return Map.of("recordId", response.getBody().getRecordId(), "requestId", response.getBody().getRequestId(), "deleted", true);
        } catch (Exception e) {
            throw remoteError(e);
        }
    }

    private Client client(McpBackendDO backend) throws Exception {
        Config config = new Config()
                .setAccessKeyId(accessKeyId(backend))
                .setAccessKeySecret(accessKeySecret(backend))
                .setSecurityToken(securityToken(backend))
                .setEndpoint(endpoint(backend, DEFAULT_ENDPOINT))
                .setConnectTimeout(intArg(extra(backend), "timeoutSeconds", 30, 1, 1800) * 1000)
                .setReadTimeout(intArg(extra(backend), "timeoutSeconds", 30, 1, 1800) * 1000);
        return new Client(config);
    }

    private Set<String> allowedDomains(McpBackendDO backend) {
        String domain = firstNonBlank(str(extra(backend), "domain"), str(extra(backend), "domainName"));
        return blank(domain) ? Set.of() : Set.of(domain);
    }

    private String resolveDomain(McpBackendDO backend, Map<String, Object> args) {
        Set<String> allowed = allowedDomains(backend);
        String configured = allowed.stream().findFirst().orElse(null);
        String domainName = firstNonBlank(configured, str(args, "domainName"));
        requireAllowed(domainName, allowed, "domainName");
        return domainName;
    }

    private BizException remoteError(Exception e) {
        return new BizException(BizErrorCode.MCP_REMOTE_ERROR, "阿里云域名调用失败: " + e.getMessage());
    }

}
