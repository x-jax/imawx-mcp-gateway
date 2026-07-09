SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_access_log` ADD COLUMN `token_prefix_snapshot` VARCHAR(16) NULL COMMENT ''请求发生时的 MCP API Token 前缀快照'' AFTER `token_id`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_access_log'
      AND column_name = 'token_prefix_snapshot'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_access_log` ADD KEY `idx_access_log_token_prefix_time` (`token_prefix_snapshot`, `create_time`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_access_log'
      AND index_name = 'idx_access_log_token_prefix_time'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;
