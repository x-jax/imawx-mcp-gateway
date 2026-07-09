SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `mcp_system_config` (
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '配置 key',
    `config_value` TEXT             NULL COMMENT '配置值',
    `description`  VARCHAR(256)     NULL COMMENT '说明',
    `updated_by`   VARCHAR(64)      NULL COMMENT '最近更新人',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置';

CREATE TABLE IF NOT EXISTS `mcp_user` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`         VARCHAR(32)  NOT NULL COMMENT '用户名',
    `password_hash`    VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码哈希',
    `display_name`     VARCHAR(64)      NULL COMMENT '显示名',
    `email`            VARCHAR(128) NOT NULL COMMENT '邮箱',
    `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    `must_change_password` TINYINT  NOT NULL DEFAULT 0 COMMENT '1=下次登录后必须修改密码',
    `last_login_at`    DATETIME         NULL COMMENT '最后登录时间',
    `totp_secret`      VARCHAR(1024)    NULL COMMENT 'RSA-OAEP 加密后的 TOTP secret',
    `totp_verified_at` DATETIME         NULL COMMENT '首次 TOTP 验证通过时间',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`),
    UNIQUE KEY `uk_user_email` (`email`),
    KEY `idx_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

CREATE TABLE IF NOT EXISTS `mcp_backend` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `backend_id`       VARCHAR(64)  NOT NULL COMMENT '业务主键',
    `created_by`       BIGINT           NULL COMMENT '创建人 userId',
    `updated_by`       BIGINT           NULL COMMENT '最近修改人 userId',
    `server_name`      VARCHAR(128) NOT NULL COMMENT '显示名',
    `transport_type`   VARCHAR(16)  NOT NULL COMMENT 'STDIO/SSE/HTTP/STREAMABLE_HTTP',
    `endpoint`         VARCHAR(512) NOT NULL COMMENT 'URL 或 STDIO 命令',
    `auth_token`       VARCHAR(1024)    NULL COMMENT '下游鉴权 token 密文',
    `extra_config`     JSON             NULL COMMENT 'STDIO args/env 等 JSON',
    `tags`             JSON             NULL COMMENT '标签 JSON 数组',
    `remark`           VARCHAR(512)     NULL COMMENT '备注',
    `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '0=DISCONNECTED 1=CONNECTED 2=FAILED',
    `enabled`          TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `health_interval`  INT         NOT NULL DEFAULT 60 COMMENT '健康探测周期秒',
    `fail_threshold`   INT         NOT NULL DEFAULT 3 COMMENT '熔断失败阈值',
    `fail_count`       INT         NOT NULL DEFAULT 0 COMMENT '连续失败次数',
    `last_check_at`    DATETIME         NULL COMMENT '最近健康探测时间',
    `last_error`       VARCHAR(1024)    NULL COMMENT '最近错误',
    `last_sync_at`     DATETIME         NULL COMMENT '最近同步时间',
    `last_sync_error`  VARCHAR(1024)    NULL COMMENT '最近同步错误',
    `tools_snapshot`   JSON             NULL COMMENT '工具快照 JSON',
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_backend_id` (`backend_id`),
    KEY `idx_backend_enabled` (`enabled`),
    KEY `idx_backend_transport` (`transport_type`),
    KEY `idx_backend_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='远端 MCP 服务配置';

CREATE TABLE IF NOT EXISTS `mcp_backend_tool` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `backend_id`   BIGINT       NOT NULL COMMENT 'mcp_backend.id',
    `tool_name`    VARCHAR(128) NOT NULL COMMENT '远端原始工具名',
    `description`  TEXT             NULL COMMENT '工具描述',
    `input_schema` JSON         NOT NULL COMMENT 'JSON Schema',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_backend_tool` (`backend_id`, `tool_name`),
    KEY `idx_backend_tool_backend` (`backend_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下游原始工具';

CREATE TABLE IF NOT EXISTS `mcp_agg_tool` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `agg_name`     VARCHAR(256) NOT NULL COMMENT '聚合工具名',
    `backend_id`   BIGINT       NOT NULL COMMENT 'mcp_backend.id',
    `tool_name`    VARCHAR(128) NOT NULL COMMENT '远端原始工具名',
    `description`  TEXT             NULL COMMENT '工具描述',
    `input_schema` JSON         NOT NULL COMMENT 'JSON Schema',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agg_name` (`agg_name`),
    KEY `idx_agg_backend` (`backend_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局聚合工具';

CREATE TABLE IF NOT EXISTS `mcp_api_token` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT       NOT NULL COMMENT 'mcp_user.id',
    `name`           VARCHAR(128) NOT NULL COMMENT 'token 名称',
    `token_prefix`   VARCHAR(16)  NOT NULL COMMENT '明文前缀',
    `token_hash`     VARCHAR(128) NOT NULL COMMENT 'SHA-256 token hash',
    `scopes`         JSON         NOT NULL COMMENT 'scope JSON 数组',
    `ip_whitelist`   JSON             NULL COMMENT '允许使用该 token 的 IP/CIDR 白名单,空为不限制',
    `expires_at`     DATETIME         NULL COMMENT '过期时间',
    `last_used_at`   DATETIME         NULL COMMENT '最近使用时间',
    `last_used_ip`   VARCHAR(45)      NULL COMMENT '最近使用 IP',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=active 0=revoked',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `revoked_at`     DATETIME         NULL COMMENT '撤销时间',
    `restrict_mode`  TINYINT      NOT NULL DEFAULT 1 COMMENT '0=全开放 1=严格授权',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_token_user` (`user_id`),
    KEY `idx_token_prefix_status` (`token_prefix`, `status`),
    KEY `idx_token_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Token';

CREATE TABLE IF NOT EXISTS `mcp_token_backend_authorization` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `token_id`    BIGINT      NOT NULL COMMENT 'mcp_api_token.id',
    `backend_id`  VARCHAR(64) NOT NULL COMMENT 'mcp_backend.backend_id',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_backend` (`token_id`, `backend_id`),
    KEY `idx_backend_auth_backend` (`backend_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token backend 授权';

CREATE TABLE IF NOT EXISTS `mcp_token_tool_authorization` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `token_id`    BIGINT       NOT NULL COMMENT 'mcp_api_token.id',
    `backend_id`  VARCHAR(64)  NOT NULL COMMENT 'mcp_backend.backend_id',
    `tool_name`   VARCHAR(128) NOT NULL COMMENT 'tool name',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_tool` (`token_id`, `backend_id`, `tool_name`),
    KEY `idx_tool_auth_backend_tool` (`backend_id`, `tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token tool 授权';

CREATE TABLE IF NOT EXISTS `mcp_session` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`       VARCHAR(64)  NOT NULL COMMENT 'MCP session id',
    `user_id`          BIGINT       NOT NULL COMMENT 'mcp_user.id',
    `token_id`         BIGINT           NULL COMMENT 'mcp_api_token.id',
    `protocol_version` VARCHAR(32)  NOT NULL COMMENT 'MCP protocol version',
    `client_info`      VARCHAR(512)     NULL COMMENT 'clientInfo JSON',
    `state`            VARCHAR(16)  NOT NULL COMMENT 'INITIALIZED/ACTIVE/CLOSED',
    `last_method`      VARCHAR(128)     NULL COMMENT '最近 method',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_active_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expire_at`        DATETIME     NOT NULL COMMENT '过期时间',
    `close_time`       DATETIME         NULL COMMENT '关闭时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_session_user` (`user_id`),
    KEY `idx_session_expire` (`expire_at`),
    KEY `idx_session_state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 协议 session';

CREATE TABLE IF NOT EXISTS `mcp_call_log` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `trace_id`            VARCHAR(64)  NOT NULL COMMENT '链路追踪 ID',
    `user_id`             BIGINT       NOT NULL COMMENT 'mcp_user.id',
    `user_email_snapshot` VARCHAR(128)     NULL COMMENT '调用时用户邮箱快照',
    `token_id`            BIGINT           NULL COMMENT 'mcp_api_token.id',
    `token_prefix_snapshot` VARCHAR(16)    NULL COMMENT '调用时 token 前缀快照',
    `backend_id`          BIGINT       NOT NULL COMMENT 'mcp_backend.id',
    `backend_key`         VARCHAR(64)      NULL COMMENT 'mcp_backend.backend_id，用于内置 MCP/聚合路由日志回填服务名',
    `server_name_snapshot` VARCHAR(128)    NULL COMMENT '调用时 MCP 服务名快照，避免 MCP 删除后审计丢失',
    `tool_name`           VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'tool name',
    `tool_description_snapshot` VARCHAR(1024) NULL COMMENT '调用时 tool 描述快照，避免 tool 删除/改名后审计丢失',
    `transport_type`      VARCHAR(16)  NOT NULL COMMENT 'HTTP/SSE/STDIO',
    `inbound_session_id`  VARCHAR(128)     NULL COMMENT '调用方 session id',
    `outbound_session_id` VARCHAR(128)     NULL COMMENT '下游 MCP session id',
    `arguments_json`      MEDIUMTEXT       NULL COMMENT '入参 JSON',
    `result_json`         MEDIUMTEXT       NULL COMMENT '结果 JSON',
    `stream_logs_json`    MEDIUMTEXT       NULL COMMENT 'stream logging/progress JSON',
    `success`             TINYINT      NOT NULL COMMENT '1成功 0失败',
    `error_code`          VARCHAR(64)      NULL COMMENT '错误码',
    `error_message`       VARCHAR(1024)    NULL COMMENT '错误信息',
    `cost_ms`             INT         NOT NULL COMMENT '耗时毫秒',
    `create_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `client_ip`           VARCHAR(45)      NULL COMMENT '调用方 IP',
    `user_agent`          VARCHAR(512)     NULL COMMENT '调用方 User-Agent',
    PRIMARY KEY (`id`),
    KEY `idx_call_user_time` (`user_id`, `create_time`),
    KEY `idx_call_user_email_time` (`user_email_snapshot`, `create_time`),
    KEY `idx_call_token_time` (`token_id`, `create_time`),
    KEY `idx_call_token_prefix_time` (`token_prefix_snapshot`, `create_time`),
    KEY `idx_call_backend_time` (`backend_id`, `create_time`),
    KEY `idx_call_backend_key_time` (`backend_key`, `create_time`),
    KEY `idx_call_server_snapshot_time` (`server_name_snapshot`, `create_time`),
    KEY `idx_call_trace` (`trace_id`),
    KEY `idx_call_tool` (`tool_name`),
    KEY `idx_call_success_cost` (`success`, `cost_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 工具调用日志';

CREATE TABLE IF NOT EXISTS `mcp_backend_extension` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `backend_id`     BIGINT       NOT NULL COMMENT 'mcp_backend.id',
    `provider_type`  VARCHAR(32)  NOT NULL COMMENT 'MYSQL/REDIS/ALIYUN_DNS/ALIYUN_OSS/SSH/DRONE 等',
    `config_json`    JSON             NULL COMMENT '非敏感扩展配置 JSON',
    `secret_enc`     MEDIUMTEXT       NULL COMMENT '敏感配置密文 JSON envelope',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_backend_ext_backend` (`backend_id`),
    KEY `idx_backend_ext_provider` (`provider_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 后端扩展配置表';

CREATE TABLE IF NOT EXISTS `mcp_tool_override` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `backend_id`   BIGINT       NOT NULL COMMENT 'mcp_backend.id',
    `tool_name`    VARCHAR(128) NOT NULL COMMENT '原始 tool 名',
    `display_name` VARCHAR(128)     NULL COMMENT '重写后的展示/暴露 tool 名',
    `description`  TEXT             NULL COMMENT '重写后的 tool 描述',
    `input_schema` JSON             NULL COMMENT '重写后的 JSON Schema',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_override_backend_tool` (`backend_id`, `tool_name`),
    UNIQUE KEY `uk_tool_override_backend_display` (`backend_id`, `display_name`),
    KEY `idx_tool_override_backend` (`backend_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Tool 元数据重写';

CREATE TABLE IF NOT EXISTS `mcp_access_log` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`            VARCHAR(64)      NULL COMMENT '链路追踪 ID',
    `ip`                  VARCHAR(64)  NOT NULL COMMENT '客户端 IP',
    `method`              VARCHAR(16)  NOT NULL COMMENT 'HTTP 方法',
    `uri`                 VARCHAR(512) NOT NULL COMMENT '请求路径，不包含 query string',
    `result`              VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILED',
    `status`              INT          NOT NULL COMMENT 'HTTP 状态码',
    `cost_ms`             INT          NOT NULL COMMENT '耗时毫秒',
    `has_query`           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否带 query string，不保存 query 原文',
    `user_agent`          VARCHAR(512)     NULL COMMENT 'User-Agent，已裁剪/清理控制字符',
    `user_id`             BIGINT           NULL COMMENT '后台用户 ID',
    `user_email_snapshot` VARCHAR(128)     NULL COMMENT '请求发生时的登录账号邮箱快照',
    `token_id`            BIGINT           NULL COMMENT 'MCP API Token ID',
    `token_prefix_snapshot` VARCHAR(16)    NULL COMMENT '请求发生时的 MCP API Token 前缀快照',
    `session_id`          VARCHAR(128)     NULL COMMENT 'HTTP Session ID',
    `auth_header`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否携带 Authorization header，不保存原文',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_access_log_create_time` (`create_time`),
    KEY `idx_access_log_ip` (`ip`),
    KEY `idx_access_log_uri` (`uri`),
    KEY `idx_access_log_status` (`status`),
    KEY `idx_access_log_result` (`result`),
    KEY `idx_access_log_trace` (`trace_id`),
    KEY `idx_access_log_user_email_time` (`user_email_snapshot`, `create_time`),
    KEY `idx_access_log_token_prefix_time` (`token_prefix_snapshot`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 请求访问日志';

CREATE TABLE IF NOT EXISTS `SPRING_SESSION` (
    `PRIMARY_ID`            CHAR(36)     NOT NULL,
    `SESSION_ID`            CHAR(36)     NOT NULL,
    `CREATION_TIME`         BIGINT       NOT NULL,
    `LAST_ACCESS_TIME`      BIGINT       NOT NULL,
    `MAX_INACTIVE_INTERVAL` INT          NOT NULL,
    `EXPIRY_TIME`           BIGINT       NOT NULL,
    `PRINCIPAL_NAME`        VARCHAR(100) NULL,
    PRIMARY KEY (`PRIMARY_ID`),
    UNIQUE KEY `SPRING_SESSION_IX1` (`SESSION_ID`),
    KEY `SPRING_SESSION_IX2` (`EXPIRY_TIME`),
    KEY `SPRING_SESSION_IX3` (`PRINCIPAL_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Spring Session JDBC 会话';

CREATE TABLE IF NOT EXISTS `SPRING_SESSION_ATTRIBUTES` (
    `SESSION_PRIMARY_ID` CHAR(36)     NOT NULL,
    `ATTRIBUTE_NAME`     VARCHAR(200) NOT NULL,
    `ATTRIBUTE_BYTES`    BLOB         NOT NULL,
    PRIMARY KEY (`SESSION_PRIMARY_ID`, `ATTRIBUTE_NAME`),
    CONSTRAINT `SPRING_SESSION_ATTRIBUTES_FK`
        FOREIGN KEY (`SESSION_PRIMARY_ID`) REFERENCES `SPRING_SESSION` (`PRIMARY_ID`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Spring Session JDBC 会话属性';

INSERT INTO `mcp_system_config` (`config_key`, `config_value`, `description`, `updated_by`)
VALUES
    ('mcp.global.enabled', '1', '全局 MCP Server 开关，1=开启 0=关闭', 'init'),
    ('mcp.session.timeout-hours', '1', 'MCP session 过期小时数', 'init'),
    ('mcp.auth.totp-enabled', '0', 'TOTP 2FA 总开关，生产启用前改为 1', 'init'),
    ('mcp.security.stdio.allowed-commands', '', 'STDIO backend 命令白名单，多个值用逗号/分号/换行分隔，必须精确匹配 endpoint', 'init'),
    ('mcp.security.redact.keys', 'password,passwd,pwd,token,access_token,refresh_token,authorization,auth_token,api_key,apikey,secret,secret_key,private_key,client_secret,totp_secret,credential', '调用日志和应用日志需要脱敏的 JSON 字段名，多个值用逗号/分号/换行分隔', 'init'),
    ('mcp.security.redact.patterns', '', '调用日志和应用日志需要脱敏的正则表达式，多个值用逗号/分号/换行分隔', 'init'),
    ('mcp.audit.access-log.retention-days', '90', '访问日志保留天数，最多 90 天', 'init'),
    ('mcp.audit.call-log.retention-days', '90', '调用日志保留天数，最多 90 天', 'init')
ON DUPLICATE KEY UPDATE
    `description` = VALUES(`description`);
