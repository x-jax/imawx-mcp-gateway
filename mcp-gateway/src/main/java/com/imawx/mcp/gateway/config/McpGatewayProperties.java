package com.imawx.mcp.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用自定义配置（对应 application.yml 的 {@code mcp-gateway.*} 段）。
 *
 * <p>阶段 1 仅 smoke-test / sync / invoke 三个子段，阶段 2/3 扩展。
 *
 * @author Liu,Dongdong
 * @since 2026-07-01
 */
@Data
@ConfigurationProperties(prefix = "mcp-gateway")
public class McpGatewayProperties {

    /** MCP 客户端相关配置。 */
    private Mcp mcp = new Mcp();

    /** 日志文件相关配置(2026-07-01 加,日志文件查看页用)。 */
    private Log log = new Log();

    /** Web 安全相关配置。 */
    private Web web = new Web();

    /** 安全相关配置。 */
    private Security security = new Security();

    @Data
    public static class Log {
        /**
         * 日志根目录(相对路径或绝对路径)。
         *
         * <p>默认值 {@code logs} —— 跟 {@code application.yml} 里
         * {@code logging.file.name: logs/mcp-gateway.log} 保持一致。
         * 后端解析成项目运行 cwd + 相对路径,所以开发期 / 生产期都不会错。
         */
        private String dir = "logs";

        /**
         * 单次 view 接口允许读取的最大行数(防越权防 OOM)。
         *
         * <p>前端传 {@code lines=N} 时后端强制不超过这个值。
         */
        private int maxTailLines = 10000;
    }

    @Data
    public static class Web {
        private Cors cors = new Cors();

        @Data
        public static class Cors {
            /**
             * 允许携带 cookie 的跨域来源白名单。不要配置为 "*"。
             */
            private String[] allowedOriginPatterns = {
                    "http://localhost:*",
                    "http://127.0.0.1:*",
                    "http://[::1]:*"
            };
        }
    }

    @Data
    public static class Mcp {

        /** 启动时是否执行内部测试 Client smoke 调用。 */
        private SmokeTest smokeTest = new SmokeTest();

        /** 工具同步相关默认。 */
        private Sync sync = new Sync();

        /** 远端调用相关默认。 */
        private Invoke invoke = new Invoke();
    }

    @Data
    public static class SmokeTest {
        /** 是否启用。 */
        private boolean enabled = false;
        /** 目标下游 ID。 */
        private String backendId = "";
        /** 目标工具名。 */
        private String toolName = "";
        /** 入参 JSON 字符串。 */
        private String argumentsJson = "{}";
    }

    @Data
    public static class Sync {
        /** 默认健康探测周期（秒）。 */
        private int defaultIntervalSeconds = 60;
        /** 默认熔断失败阈值。 */
        private int defaultFailThreshold = 3;
    }

    @Data
    public static class Invoke {
        /**
         * 单次工具调用请求超时（秒）。
         *
         * <p>2026-07-03 改：默认值从 {@code 10} → {@code 1800}（30 分钟）。
         * 原因：MCP gateway 转发到下游 MCP server 的工具调用可能是慢查询（OLAP、大模型推理、批处理），
         * 10s 远远不够。30 分钟是上游技术上限 —— 网关最多等远端 30 分钟。
         *
         * <p>2026-07-03 同步：{@code McpCallLogService.TIMEOUT_THRESHOLD_MS} 也从 60_000
         * 改为 1_800_000（30 分钟），让技术 timeout 和统计阈值保持一致 —— 慢查询
         * 跑 5 分钟在 dashboard 里归 SUCCESS（没超过 30 分钟统计阈值）。
         */
        private int requestTimeoutSeconds = 1800;
        /**
         * initialize 握手超时（秒）。
         *
         * <p>2026-07-03 改：默认值从 {@code 10} → {@code 1800}（30 分钟），跟 request 对齐。
         * 远端 MCP server 启动慢 / 资源紧张的极端场景留足时间。
         */
        private int initializeTimeoutSeconds = 1800;

        /** OSS 文件上传工具允许的最大文件字节数。 */
        private int ossMaxUploadBytes = 10 * 1024 * 1024;
    }

    /** 安全策略配置。 */
    @Data
    public static class Security {
        /**
         * 是否强制所有请求使用 HTTPS。生产必须开启。
         */
        private boolean requireHttps = false;

        /**
         * 允许信任 X-Forwarded-Proto 的反向代理地址或 CIDR。
         */
        private List<String> trustedProxyCidrs = new ArrayList<>(List.of(
                "127.0.0.1/32",
                "::1/128"
        ));

        private BackendTarget backendTarget = new BackendTarget();
        private Audit audit = new Audit();
        private Csrf csrf = new Csrf();

        @Data
        public static class BackendTarget {
            /**
             * 是否允许 STDIO backend。STDIO 会在网关主机启动子进程，默认关闭。
             */
            private boolean allowStdio = false;

            /**
             * 是否允许 HTTP/SSE endpoint 指向 localhost / loopback。
             */
            private boolean allowLoopback = false;

            /**
             * 是否允许 HTTP/SSE endpoint 指向 link-local 地址，例如云 metadata 网段。
             */
            private boolean allowLinkLocal = false;
        }

        @Data
        public static class Audit {
            /**
             * 兼容旧配置。调用日志必须保留 payload，实际写入前由配置表脱敏。
             */
            private boolean persistPayloads = true;
        }

        @Data
        public static class Csrf {
            /**
             * 是否启用后台管理 API 的 CSRF 防护。生产必须开启。
             */
            private boolean enabled = false;
        }
    }
}
