UPDATE `mcp_api_token`
SET `status` = 0,
    `revoked_at` = COALESCE(`revoked_at`, NOW())
WHERE `status` = 1
  AND `token_hash` NOT REGEXP '^[0-9a-f]{64}$';

SET @imawx_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `mcp_api_token` ADD UNIQUE KEY `uk_token_hash` (`token_hash`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'mcp_api_token'
      AND index_name = 'uk_token_hash'
);
PREPARE imawx_stmt FROM @imawx_sql;
EXECUTE imawx_stmt;
DEALLOCATE PREPARE imawx_stmt;
