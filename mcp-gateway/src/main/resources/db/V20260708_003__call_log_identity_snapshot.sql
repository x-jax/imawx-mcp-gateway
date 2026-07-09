SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD COLUMN `user_email_snapshot` VARCHAR(128) NULL COMMENT ''调用时用户邮箱快照'' AFTER `user_id`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND column_name = 'user_email_snapshot'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD COLUMN `token_id` BIGINT NULL COMMENT ''mcp_api_token.id'' AFTER `user_email_snapshot`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND column_name = 'token_id'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD COLUMN `token_prefix_snapshot` VARCHAR(16) NULL COMMENT ''调用时 token 前缀快照'' AFTER `token_id`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND column_name = 'token_prefix_snapshot'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD KEY `idx_call_user_email_time` (`user_email_snapshot`, `create_time`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND index_name = 'idx_call_user_email_time'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD KEY `idx_call_token_time` (`token_id`, `create_time`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND index_name = 'idx_call_token_time'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_call_log` ADD KEY `idx_call_token_prefix_time` (`token_prefix_snapshot`, `create_time`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_call_log'
      AND index_name = 'idx_call_token_prefix_time'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;
