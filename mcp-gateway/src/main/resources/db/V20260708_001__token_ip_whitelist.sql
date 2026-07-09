SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_api_token` ADD COLUMN `ip_whitelist` JSON NULL COMMENT ''允许使用该 token 的 IP/CIDR 白名单,空为不限制'' AFTER `scopes`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_api_token'
      AND column_name = 'ip_whitelist'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;
