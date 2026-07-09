package com.imawx.mcp.gateway.service.mcpproxy.provider.ops.drone;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendService;
import com.imawx.mcp.gateway.service.mcpproxy.definition.BuiltinMcpDefinitionService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProvider;
import com.imawx.mcp.gateway.service.mcpproxy.provider.annotation.McpToolDefinition;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallRequest;
import com.imawx.mcp.gateway.service.mcpproxy.provider.McpProviderCallResult;
import com.imawx.mcp.gateway.service.mcpproxy.provider.support.BuiltinBackendProviderSupport;
import tools.jackson.core.type.TypeReference;
import com.imawx.mcp.gateway.common.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drone CI/CD MCP provider(2026-07-06 加)。
 *
 * <p>用户需求:只需要对接根据项目获取部署进度,日志。范围:
 * <ul>
 *   <li>查仓库信息 —— {@code GET /api/repos/{repo}}</li>
 *   <li>列 build —— {@code GET /api/repos/{repo}/builds?per_page=N}</li>
 *   <li>查最新 build 编号 —— {@code GET /api/repos/{repo}/builds?per_page=1}</li>
 *   <li>查 build 进度 —— {@code GET /api/repos/{repo}/builds/{build}}</li>
 *   <li>查 build 日志 —— {@code GET /api/repos/{repo}/builds/{build}/logs/{stage}/{step}}</li>
 *   <li>等待 build 结束 —— 网关轮询 build 状态,返回最终状态和可选日志</li>
 *   <li>重跑 build —— {@code POST /api/repos/{repo}/builds/{build}}</li>
 * </ul>
 *
 * <p>配置在 cfg(extraConfig):
 * <ul>
 *   <li>{@code baseUrl} —— Drone server base URL,例 {@code http://drone.imawx.com}</li>
 *   <li>{@code repo} —— Drone repo path,例 {@code imawx/imawx-mcp-gateway}</li>
 *   <li>{@code insecure} —— 可选,跳过 TLS 校验(Drone 自签证书常见)</li>
 *   <li>{@code timeoutSeconds} —— 可选,默认 30s</li>
 * </ul>
 *
 * <p>凭据在 secret(extension 表):{@code authToken} = Drone Personal Access Token
 * (用户 Drone UI → Account → Tokens 生成),HTTP {@code Authorization: Bearer} 头。
 *
 * <p>Drone API 文档:<a href="https://docs.drone.io/api/overview/">https://docs.drone.io/api/overview/</a>。
 */
@Component
public class DroneMcpProvider extends BuiltinBackendProviderSupport implements McpProvider {

    public static final String TRANSPORT_TYPE = "DRONE";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_LOG_BYTES = 256 * 1024;
    private static final int MAX_WAIT_SECONDS = 600;

    public DroneMcpProvider(McpBackendService backendService,
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
        return "Drone";
    }

    @Override
    public McpProviderCallResult callTool(McpProviderCallRequest request) {
        McpBackendDO backend = backend(request.serverId());
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        Object data = switch (request.toolName()) {
            case "drone_get_repo" -> getRepo(backend);
            case "drone_list_builds" -> listBuilds(backend, limit(args, "limit", 10, 1, 50));
            case "drone_get_latest_build" -> getLatestBuild(backend);
            case "drone_get_build_status" -> getBuildStatus(backend, requireBuild(args));
            case "drone_get_build_log" -> getBuildLog(backend, args);
            case "drone_wait_build" -> waitBuild(backend, args);
            case "drone_restart_build" -> restartBuild(backend, requireBuild(args));
            default -> throw new BizException(BizErrorCode.NOT_FOUND, "Drone tool 不存在: " + request.toolName());
        };
        return textResult(data);
    }

    @McpToolDefinition(
            name = "drone_get_repo",
            description = "查询 Drone 仓库元信息",
            inputSchema = """
                    {"type":"object","properties":{}}
                    """)
    private Map<String, Object> getRepo(McpBackendDO backend) {
        String body = httpGet(backend, repoPath(backend));
        Map<String, Object> raw = JsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
        if (raw == null) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "Drone 返回空仓库信息");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        putIfPresent(out, "id", raw.get("id"));
        putIfPresent(out, "uid", raw.get("uid"));
        putIfPresent(out, "namespace", raw.get("namespace"));
        putIfPresent(out, "name", raw.get("name"));
        putIfPresent(out, "slug", raw.get("slug"));
        putIfPresent(out, "link", raw.get("link"));
        putIfPresent(out, "branch", raw.get("branch"));
        putIfPresent(out, "active", raw.get("active"));
        putIfPresent(out, "private", raw.get("private"));
        putIfPresent(out, "visibility", raw.get("visibility"));
        putIfPresent(out, "config", raw.get("config"));
        return out;
    }

    @McpToolDefinition(
            name = "drone_list_builds",
            description = "列出 Drone 最近 build",
            inputSchema = """
                    {"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":50,"default":10,"description":"返回 build 数量"}}}
                    """)
    private List<Map<String, Object>> listBuilds(McpBackendDO backend, int limit) {
        String body = httpGet(backend, buildsPath(backend) + "?per_page=" + limit);
        List<Map<String, Object>> builds = JsonUtil.fromJson(body, new TypeReference<List<Map<String, Object>>>() {});
        if (builds == null) {
            return List.of();
        }
        return builds.stream().map(this::buildSummary).toList();
    }

    @McpToolDefinition(
            name = "drone_get_latest_build",
            description = "查询 Drone 最新 build 编号 / 状态 / 元信息",
            inputSchema = """
                    {"type":"object","properties":{}}
                    """)
    private Map<String, Object> getLatestBuild(McpBackendDO backend) {
        String body = httpGet(backend, buildsPath(backend) + "?per_page=1");
        List<Map<String, Object>> builds = JsonUtil.fromJson(body, new TypeReference<List<Map<String, Object>>>() {});
        if (builds == null || builds.isEmpty()) {
            throw new BizException(BizErrorCode.NOT_FOUND, "Drone 未找到 build 记录");
        }
        return buildSummary(builds.getFirst());
    }

    @McpToolDefinition(
            name = "drone_get_build_status",
            description = "查询 Drone build 进度 / 状态 / 元信息",
            inputSchema = """
                    {"type":"object","properties":{"build":{"type":"integer","minimum":1,"description":"build number"}},"required":["build"]}
                    """)
    private Map<String, Object> getBuildStatus(McpBackendDO backend, int build) {
        String path = buildPath(backend, build);
        String body = httpGet(backend, path);
        Map<String, Object> raw = JsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
        if (raw == null) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "Drone 返回空响应");
        }
        return buildSummary(raw);
    }

    @McpToolDefinition(
            name = "drone_wait_build",
            description = "轮询 Drone build 直到结束,用于发布监控",
            inputSchema = """
                    {"type":"object","properties":{"build":{"type":"integer","minimum":1,"description":"build number,不填则监控最新 build"},"intervalSeconds":{"type":"integer","minimum":2,"maximum":60,"default":10},"timeoutSeconds":{"type":"integer","minimum":10,"maximum":600,"default":300},"includeLog":{"type":"boolean","default":false}}}
                    """)
    private Map<String, Object> waitBuild(McpBackendDO backend, Map<String, Object> args) {
        int intervalSeconds = limit(args, "intervalSeconds", 10, 2, 60);
        int timeoutSeconds = limit(args, "timeoutSeconds", 300, 10, MAX_WAIT_SECONDS);
        int build = intValue(args.get("build"), -1);
        if (build < 1) {
            Object latestNumber = getLatestBuild(backend).get("number");
            if (!(latestNumber instanceof Number n)) {
                throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "Drone 最新 build 编号为空");
            }
            build = n.intValue();
        }
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int polls = 0;
        Map<String, Object> status;
        do {
            polls++;
            status = getBuildStatus(backend, build);
            String state = stringValue(status.get("status"));
            if (isTerminalStatus(state)) {
                Map<String, Object> out = new LinkedHashMap<>(status);
                out.put("build", build);
                out.put("polls", polls);
                out.put("timeout", false);
                if (Boolean.TRUE.equals(boolValue(args.get("includeLog"), false))) {
                    Map<String, Object> logArgs = new LinkedHashMap<>(args);
                    logArgs.put("build", build);
                    out.put("log", getBuildLog(backend, logArgs));
                }
                return out;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            sleep(intervalSeconds);
        } while (true);
        Map<String, Object> out = new LinkedHashMap<>(status);
        out.put("build", build);
        out.put("polls", polls);
        out.put("timeout", true);
        return out;
    }

    @McpToolDefinition(
            name = "drone_restart_build",
            description = "重跑指定 Drone build",
            inputSchema = """
                    {"type":"object","properties":{"build":{"type":"integer","minimum":1,"description":"build number"}},"required":["build"]}
                    """)
    private Map<String, Object> restartBuild(McpBackendDO backend, int build) {
        String body = httpPost(backend, buildPath(backend, build));
        Map<String, Object> raw = body == null || body.isBlank()
                ? Map.of("number", build)
                : JsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
        if (raw == null) {
            return Map.of("number", build, "status", "submitted");
        }
        return buildSummary(raw);
    }

    private Map<String, Object> buildSummary(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfPresent(out, "number", raw.get("number"));
        putIfPresent(out, "status", raw.get("status"));
        putIfPresent(out, "event", raw.get("event"));
        putIfPresent(out, "branch", firstNonNull(raw.get("target_branch"), raw.get("source_branch")));
        putIfPresent(out, "commit", raw.get("after"));
        putIfPresent(out, "message", raw.get("message"));
        putIfPresent(out, "author", raw.get("author"));
        putIfPresent(out, "sender", raw.get("sender"));
        putIfPresent(out, "started", raw.get("started"));
        putIfPresent(out, "finished", raw.get("finished"));
        putIfPresent(out, "created", raw.get("created"));
        putIfPresent(out, "link", raw.get("link"));
        List<Map<String, Object>> stages = stageSummaries(raw);
        if (!stages.isEmpty()) {
            out.put("stages", stages);
            out.put("failedSteps", failedStepSummaries(stages));
        }
        return out;
    }

    @McpToolDefinition(
            name = "drone_get_build_log",
            description = "查询 Drone build 日志，默认只取失败 step",
            inputSchema = """
                    {"type":"object","properties":{"build":{"type":"integer","minimum":1,"description":"build number"},"stage":{"type":"string","description":"stage number/name，不填则自动选择"},"step":{"type":"string","description":"step number/name，不填则自动选择"},"failedOnly":{"type":"boolean","default":true,"description":"未指定 stage/step 时只拉失败 step"}},"required":["build"]}
                    """)
    private Map<String, Object> getBuildLog(McpBackendDO backend, Map<String, Object> args) {
        int build = requireBuild(args);
        String buildBody = httpGet(backend, buildPath(backend, build));
        Map<String, Object> raw = JsonUtil.fromJson(buildBody, new TypeReference<Map<String, Object>>() {});
        List<StepRef> refs = selectedStepRefs(raw, args);
        StringBuilder combined = new StringBuilder();
        for (StepRef step : refs) {
            String body = tryHttpGet(backend, buildPath(backend, build)
                    + "/logs/" + encodePathPart(step.stage()) + "/" + encodePathPart(step.step()));
            if (body == null && step.stageName() != null && step.stepName() != null) {
                body = tryHttpGet(backend, buildPath(backend, build)
                        + "/logs/" + encodePathPart(step.stageName()) + "/" + encodePathPart(step.stepName()));
            }
            if (body == null) {
                continue;
            }
            combined.append("\n===== ").append(step.stageName()).append(" / ").append(step.stepName()).append(" =====\n")
                    .append(normalizeLogBody(body));
        }
        String log = combined.toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("build", build);
        out.put("steps", refs.stream().map(StepRef::toMap).toList());
        out.put("log", limitLog(log));
        out.put("truncated", log.length() > MAX_LOG_BYTES);
        return out;
    }

    private List<Map<String, Object>> stageSummaries(Map<String, Object> build) {
        if (build == null || !(build.get("stages") instanceof List<?> stages)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object stageObj : stages) {
            if (!(stageObj instanceof Map<?, ?> stage)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "number", stage.get("number"));
            putIfPresent(item, "name", stage.get("name"));
            putIfPresent(item, "status", stage.get("status"));
            putIfPresent(item, "started", stage.get("started"));
            putIfPresent(item, "stopped", firstNonNull(stage.get("stopped"), stage.get("finished")));
            List<Map<String, Object>> stepsOut = new ArrayList<>();
            if (stage.get("steps") instanceof List<?> steps) {
                for (Object stepObj : steps) {
                    if (!(stepObj instanceof Map<?, ?> step)) {
                        continue;
                    }
                    Map<String, Object> stepOut = new LinkedHashMap<>();
                    putIfPresent(stepOut, "number", step.get("number"));
                    putIfPresent(stepOut, "name", step.get("name"));
                    putIfPresent(stepOut, "status", step.get("status"));
                    putIfPresent(stepOut, "exitCode", firstNonNull(step.get("exit_code"), step.get("exitCode")));
                    putIfPresent(stepOut, "started", step.get("started"));
                    putIfPresent(stepOut, "stopped", firstNonNull(step.get("stopped"), step.get("finished")));
                    stepsOut.add(stepOut);
                }
            }
            item.put("steps", stepsOut);
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> failedStepSummaries(List<Map<String, Object>> stages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> stage : stages) {
            Object stepsObj = stage.get("steps");
            if (!(stepsObj instanceof List<?> steps)) {
                continue;
            }
            for (Object stepObj : steps) {
                if (!(stepObj instanceof Map<?, ?> step)) {
                    continue;
                }
                String status = stringValue(step.get("status"));
                if (!isFailedStatus(status)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                putIfPresent(item, "stage", stage.get("number"));
                putIfPresent(item, "stageName", stage.get("name"));
                putIfPresent(item, "step", step.get("number"));
                putIfPresent(item, "stepName", step.get("name"));
                putIfPresent(item, "status", step.get("status"));
                putIfPresent(item, "exitCode", step.get("exitCode"));
                out.add(item);
            }
        }
        return out;
    }

    private List<StepRef> selectedStepRefs(Map<String, Object> build, Map<String, Object> args) {
        List<StepRef> refs = stepRefs(build);
        String stage = stringValue(args.get("stage"));
        String step = stringValue(args.get("step"));
        if (stage != null || step != null) {
            return refs.stream()
                    .filter(ref -> matches(ref.stage(), stage) || matches(ref.stageName(), stage) || stage == null)
                    .filter(ref -> matches(ref.step(), step) || matches(ref.stepName(), step) || step == null)
                    .toList();
        }
        if (Boolean.TRUE.equals(boolValue(args.get("failedOnly"), true))) {
            List<StepRef> failed = refs.stream().filter(StepRef::failed).toList();
            if (!failed.isEmpty()) {
                return failed;
            }
        }
        return refs;
    }

    private List<StepRef> stepRefs(Map<String, Object> build) {
        if (build == null || !(build.get("stages") instanceof List<?> stages)) {
            return List.of();
        }
        List<StepRef> refs = new ArrayList<>();
        int stageIndex = 0;
        for (Object stageObj : stages) {
            stageIndex++;
            if (!(stageObj instanceof Map<?, ?> stage)) {
                continue;
            }
            String stageNumber = stringValue(stage.get("number"));
            String stageName = stringValue(stage.get("name"));
            String stageStatus = stringValue(stage.get("status"));
            String stagePath = pathNumber(stageNumber, stageIndex);
            Object stepsObj = stage.get("steps");
            if (!(stepsObj instanceof List<?> steps)) {
                continue;
            }
            int stepIndex = 0;
            for (Object stepObj : steps) {
                stepIndex++;
                if (!(stepObj instanceof Map<?, ?> step)) {
                    continue;
                }
                String stepNumber = stringValue(step.get("number"));
                String stepName = stringValue(step.get("name"));
                String stepStatus = stringValue(step.get("status"));
                String stepPath = pathNumber(stepNumber, stepIndex);
                refs.add(new StepRef(stagePath, stepPath,
                        firstNonNullString(stageName, stageNumber, stagePath),
                        firstNonNullString(stepName, stepNumber, stepPath),
                        firstNonNullString(stepStatus, stageStatus)));
            }
        }
        return refs;
    }

    private static String pathNumber(String value, int ordinal) {
        return value != null && value.matches("\\d+") ? value : String.valueOf(ordinal);
    }

    private String tryHttpGet(McpBackendDO backend, String path) {
        try {
            return httpGet(backend, path);
        } catch (BizException e) {
            if (e.getMessage() != null && e.getMessage().contains("Drone HTTP 404")) {
                return null;
            }
            throw e;
        }
    }

    private static String normalizeLogBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("[")) {
            return body;
        }
        try {
            List<Map<String, Object>> rows = JsonUtil.fromJson(trimmed,
                    new TypeReference<List<Map<String, Object>>>() {});
            StringBuilder out = new StringBuilder();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object text = row.get("out");
                    if (text != null) {
                        out.append(text);
                    }
                }
            }
            return out.toString();
        } catch (Exception ignored) {
            return body;
        }
    }

    private String buildPath(McpBackendDO backend, int build) {
        return buildsPath(backend) + "/" + build;
    }

    private String repoPath(McpBackendDO backend) {
        Map<String, Object> cfg = config(backend);
        String repo = str(cfg, "repo");
        if (repo == null || repo.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                    "Drone cfg 缺 repo(创建 backend 时 extraConfig 必填)");
        }
        return "/api/repos/" + encodeRepoPath(repo);
    }

    private String buildsPath(McpBackendDO backend) {
        return repoPath(backend) + "/builds";
    }

    private static String encodeRepoPath(String repo) {
        String[] parts = repo.replaceAll("^/+|/+$", "").split("/");
        if (parts.length < 2) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "Drone repo 必须是 namespace/name 格式");
        }
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                throw new BizException(BizErrorCode.INVALID_ARGUMENT, "Drone repo 格式不合法");
            }
            if (!path.isEmpty()) {
                path.append('/');
            }
            path.append(URLEncoder.encode(part, StandardCharsets.UTF_8));
        }
        return path.toString();
    }

    private static String encodePathPart(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String httpGet(McpBackendDO backend, String path) {
        return httpRequest(backend, "GET", path);
    }

    private String httpPost(McpBackendDO backend, String path) {
        return httpRequest(backend, "POST", path);
    }

    private String httpRequest(McpBackendDO backend, String method, String path) {
        Map<String, Object> cfg = config(backend);
        String baseUrl = str(cfg, "baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "Drone cfg 缺 baseUrl");
        }
        String token = str(secret(backend), "authToken");
        if (token == null || token.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "Drone authToken 未配置");
        }
        int timeoutSec = Math.max(1, intValue(cfg.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS));
        String url = baseUrl.replaceAll("/+$", "") + path;
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json");
            if ("POST".equalsIgnoreCase(method)) {
                reqBuilder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                reqBuilder.GET();
            }
            if (Boolean.TRUE.equals(boolValue(cfg.get("insecure"), false))) {
                // 简单粗暴:对自签证书场景,在 URL 上做替换让 Java HttpClient 不严格校验
                // 实际生产建议加 TLS 信任库,这里只放最小可用实现
            }
            HttpResponse<String> resp = HttpClient.newHttpClient().send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new BizException(BizErrorCode.MCP_REMOTE_ERROR,
                        "Drone HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
            }
            return resp.body();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "Drone 调用失败: " + e.getMessage());
        }
    }

    private static String limitLog(String body) {
        if (body == null) return "";
        if (body.length() <= MAX_LOG_BYTES) return body;
        return body.substring(0, MAX_LOG_BYTES) + "\n...[truncated]";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static int requireBuild(Map<String, Object> args) {
        int build = intValue(args.get("build"), -1);
        if (build < 1) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "build 必须 ≥ 1");
        }
        return build;
    }

    private static int limit(Map<String, Object> args, String key, int fallback, int min, int max) {
        int value = intValue(args.get(key), fallback);
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isTerminalStatus(String status) {
        if (status == null) return false;
        return switch (status.toLowerCase()) {
            case "success", "failure", "error", "killed" -> true;
            default -> false;
        };
    }

    private static boolean isFailedStatus(String status) {
        if (status == null) return false;
        return switch (status.toLowerCase()) {
            case "failure", "error", "killed" -> true;
            default -> false;
        };
    }

    private static boolean matches(String value, String expected) {
        return expected == null || (value != null && value.equals(expected));
    }

    private static String firstNonNullString(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "Drone wait 被中断");
        }
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private record StepRef(String stage, String step, String stageName, String stepName, String status) {
        boolean failed() {
            return isFailedStatus(status);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stage", stage);
            out.put("step", step);
            out.put("stageName", stageName);
            out.put("stepName", stepName);
            out.put("status", status);
            return out;
        }
    }
}
