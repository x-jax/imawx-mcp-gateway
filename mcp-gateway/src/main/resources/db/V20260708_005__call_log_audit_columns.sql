SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD COLUMN `backend_key` VARCHAR(64) NULL COMMENT ''mcp_backend.backend_id，用于内置 MCP/聚合路由日志回填服务名'' AFTER `backend_id`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND column_name = 'backend_key'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD COLUMN `server_name_snapshot` VARCHAR(128) NULL COMMENT ''调用时 MCP 服务名快照，避免 MCP 删除后审计丢失'' AFTER `backend_key`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND column_name = 'server_name_snapshot'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD COLUMN `tool_description_snapshot` VARCHAR(1024) NULL COMMENT ''调用时 tool 描述快照，避免 tool 删除/改名后审计丢失'' AFTER `tool_name`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND column_name = 'tool_description_snapshot'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD COLUMN `client_ip` VARCHAR(45) NULL COMMENT ''调用方 IP'' AFTER `create_time`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND column_name = 'client_ip'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD COLUMN `user_agent` VARCHAR(512) NULL COMMENT ''调用方 User-Agent'' AFTER `client_ip`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND column_name = 'user_agent'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD KEY `idx_call_backend_key_time` (`backend_key`, `create_time`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND index_name = 'idx_call_backend_key_time'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD KEY `idx_call_server_snapshot_time` (`server_name_snapshot`, `create_time`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND index_name = 'idx_call_server_snapshot_time'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;
