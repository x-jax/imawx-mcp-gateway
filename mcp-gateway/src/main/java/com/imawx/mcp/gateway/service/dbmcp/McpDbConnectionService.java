package com.imawx.mcp.gateway.service.dbmcp;

import com.imawx.mcp.gateway.common.enums.BizErrorCode;
import com.imawx.mcp.gateway.common.exception.BizException;
import com.imawx.mcp.gateway.common.security.RsaOaepCipher;
import com.imawx.mcp.gateway.entity.do_.McpDbConnectionDO;
import com.imawx.mcp.gateway.entity.do_.McpBackendDO;
import com.imawx.mcp.gateway.entity.vo.McpDbSchemaVO;
import com.imawx.mcp.gateway.service.mcpproxy.McpBackendExtensionService;
import com.imawx.mcp.gateway.service.mcpproxy.provider.annotation.McpToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpDbConnectionService {
    private static final int MAX_QUERY_ROWS = 200;

    private final McpBackendExtensionService extensionService;
    private final RsaOaepCipher cipher;

    public Map<String, Object> callTool(McpBackendDO backend, String toolName, Map<String, Object> args) {
        return callTool(fromBackendExtension(backend), toolName, args);
    }

    private Map<String, Object> callTool(McpDbConnectionDO db, String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "list_tables" -> listTables(db);
            case "describe_table" -> describeTable(db, requireString(args, "table"));
            case "query_select" -> querySelect(db, requireString(args, "sql"), intArg(args, "limit", 50));
            case "insert_row" -> insertRow(db, requireString(args, "table"), objectMap(args, "values"));
            case "update_rows" -> updateRows(db, requireString(args, "table"), objectMap(args, "values"), objectMap(args, "where"));
            case "delete_rows" -> deleteRows(db, requireString(args, "table"), objectMap(args, "where"));
            case "execute_dml" -> executeDml(db, requireString(args, "sql"));
            default -> throw new BizException(BizErrorCode.NOT_FOUND, "数据库 tool 不存在: " + toolName);
        };
    }

    private McpDbConnectionDO fromBackendExtension(McpBackendDO backend) {
        if (backend == null || backend.getId() == null) {
            throw new BizException(BizErrorCode.NOT_FOUND, "数据库 MCP backend 不存在");
        }
        Map<String, Object> config = extensionService.config(backend.getId());
        Map<String, Object> secret = extensionService.secret(backend.getId());
        String dbType = firstNonBlank(Objects.toString(config.get("dbType"), null), backend.getTransportType());
        String jdbcUrl = Objects.toString(config.get("jdbcUrl"), null);
        String username = Objects.toString(config.get("username"), null);
        String password = Objects.toString(secret.get("password"), null);
        if (dbType == null || jdbcUrl == null || jdbcUrl.isBlank()
                || username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT,
                    "数据库 MCP 扩展配置不完整，请重新编辑保存: " + backend.getServerName());
        }
        McpDbConnectionDO db = new McpDbConnectionDO();
        db.setId(backend.getId());
        db.setConnName(backend.getServerName());
        db.setDbType(dbType.trim().toUpperCase(java.util.Locale.ROOT));
        db.setJdbcUrl(jdbcUrl.trim());
        db.setUsername(username.trim());
        db.setPasswordEnc(cipher.encrypt(password));
        db.setSchemaName(blankToNull(Objects.toString(config.get("schemaName"), null)));
        db.setEnabled(backend.getEnabled());
        return db;
    }

    private McpDbSchemaVO introspect(McpDbConnectionDO db) {
        try (Connection conn = open(db)) {
            DatabaseMetaData meta = conn.getMetaData();
            String schema = effectiveSchema(db, conn);
            List<McpDbSchemaVO.Table> tables = new ArrayList<>();
            try (ResultSet rs = meta.getTables(conn.getCatalog(), schema, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next() && tables.size() < 500) {
                    String tableName = rs.getString("TABLE_NAME");
                    tables.add(McpDbSchemaVO.Table.builder()
                            .name(tableName)
                            .type(rs.getString("TABLE_TYPE"))
                            .remarks(rs.getString("REMARKS"))
                            .columns(columns(meta, conn.getCatalog(), schema, tableName))
                            .build());
                }
            }
            return McpDbSchemaVO.builder()
                    .dbConnId(db.getId())
                    .connName(db.getConnName())
                    .dbType(db.getDbType())
                    .refreshedAt(LocalDateTime.now())
                    .tables(tables)
                    .build();
        } catch (SQLException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "数据库连接/采集失败: " + e.getMessage());
        }
    }

    @McpToolDefinition(
            name = "list_tables",
            description = "列出当前数据库的表/视图名称、类型、表注释和字段数量，协助快速定位业务表",
            inputSchema = """
                    {"type":"object","properties":{}}
                    """,
            transports = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER"})
    private Map<String, Object> listTables(McpDbConnectionDO db) {
        McpDbSchemaVO schema = introspect(db);
        List<Map<String, Object>> tables = new ArrayList<>();
        List<String> tableNames = new ArrayList<>();
        for (McpDbSchemaVO.Table table : schema.getTables()) {
            tableNames.add(table.getName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", table.getName());
            item.put("type", table.getType());
            item.put("remarks", table.getRemarks());
            item.put("columnCount", table.getColumns() == null ? 0 : table.getColumns().size());
            tables.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dbConnId", schema.getDbConnId());
        out.put("connName", schema.getConnName());
        out.put("dbType", schema.getDbType());
        out.put("refreshedAt", schema.getRefreshedAt());
        out.put("count", tables.size());
        out.put("tables", tables);
        out.put("tableNames", tableNames);
        return out;
    }

    @McpToolDefinition(
            name = "describe_table",
            description = "查看指定表字段、类型、主键和备注",
            inputSchema = """
                    {"type":"object","properties":{"table":{"type":"string"}},"required":["table"]}
                    """,
            transports = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER"})
    private Map<String, Object> describeTable(McpDbConnectionDO db, String table) {
        McpDbSchemaVO schema = introspect(db);
        return schema.getTables().stream()
                .filter(t -> t.getName().equalsIgnoreCase(table))
                .findFirst()
                .<Map<String, Object>>map(t -> Map.of("table", t.getName(), "columns", t.getColumns()))
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "表不存在: " + table));
    }

    @McpToolDefinition(
            name = "query_select",
            description = "执行 SELECT 查询，默认最多 50 行，最多 200 行",
            inputSchema = """
                    {"type":"object","properties":{"sql":{"type":"string"},"limit":{"type":"integer","maximum":200}},"required":["sql"]}
                    """,
            transports = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER"})
    private Map<String, Object> querySelect(McpDbConnectionDO db, String sql, int limit) {
        validateReadOnlySelect(sql);
        int maxRows = Math.clamp(limit, 1, MAX_QUERY_ROWS);
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setMaxRows(maxRows);
            ps.setQueryTimeout(30);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    columns.add(md.getColumnLabel(i));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        item.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(item);
                }
                return Map.of("columns", columns, "rows", rows, "rowCount", rows.size(), "limited", maxRows);
            }
        } catch (SQLException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "SQL 执行失败: " + e.getMessage());
        }
    }

    @McpToolDefinition(
            name = "insert_row",
            description = "向指定表插入一行数据",
            inputSchema = """
                    {"type":"object","properties":{"table":{"type":"string"},"values":{"type":"object","additionalProperties":true}},"required":["table","values"]}
                    """,
            transports = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER"})
    private Map<String, Object> insertRow(McpDbConnectionDO db, String table, Map<String, Object> values) {
        if (values.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "values 不能为空");
        }
        validateTableAndColumns(db, table, values.keySet().stream().toList());
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            columns.add(quoteIdentifier(entry.getKey()));
            placeholders.add("?");
            params.add(entry.getValue());
        }
        String sql = "INSERT INTO " + quoteIdentifier(table) + " (" + columns + ") VALUES (" + placeholders + ")";
        return executePreparedUpdate(db, sql, params);
    }

    @McpToolDefinition(
            name = "update_rows",
            description = "按等值 where 条件更新指定表数据",
            inputSchema = """
                    {"type":"object","properties":{"table":{"type":"string"},"values":{"type":"object","additionalProperties":true},"where":{"type":"object","additionalProperties":true}},"required":["table","values","where"]}
                    """,
            transports = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER"})
    private Map<String, Object> updateRows(McpDbConnectionDO db, String table, Map<String, Object> values, Map<String, Object> where) {
        if (values.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "values 不能为空");
        }
        if (where.isEmpty()) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "where 不能为空，禁止无条件 UPDATE");
        }
        List<String> columns = new ArrayList<>();
        columns.addAll(values.keySet());
        columns.addAll(where.keySet());
        validateTableAndColumns(db, table, columns);
        StringJoiner set = new StringJoiner(", ");
        StringJoiner conditions = new StringJoiner(" AND ");
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            set.add(quoteIdentifier(entry.getKey()) + " = ?");
            params.add(entry.getValue());
        }
        for (Map.Entry<String, Object> entry : where.entrySet()) {
            conditions.add(quoteIdentifier(entry.getKey()) + " = ?");
            params.add(entry.getValue());
        }
        String sql = "UPDATE " + quoteIdentifier(table) + " SET " + set + " WHERE " + conditions;
        return executePreparedUpdate(db, sql, params);
    }

    @McpToolDefinition(
            name = "delete_rows",
            description = "按等值 where 条件删除指定表数据",
            inputSchema = """
                    {"type":"object","properties":{"table":{"type":"string"},"where":{"type":"object","additionalProperties":true}},"required":["table","where"]}
                    """,
            transports = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER"})
    private Map<String, Object> deleteRows(McpDbConnectionDO db, String table, Map<String, Object> where) {
        if (where.isEmpty()) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "where 不能为空，禁止无条件 DELETE");
        }
        validateTableAndColumns(db, table, where.keySet().stream().toList());
        StringJoiner conditions = new StringJoiner(" AND ");
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : where.entrySet()) {
            conditions.add(quoteIdentifier(entry.getKey()) + " = ?");
            params.add(entry.getValue());
        }
        String sql = "DELETE FROM " + quoteIdentifier(table) + " WHERE " + conditions;
        return executePreparedUpdate(db, sql, params);
    }

    @McpToolDefinition(
            name = "execute_dml",
            description = "执行单条 INSERT / UPDATE / DELETE 语句",
            inputSchema = """
                    {"type":"object","properties":{"sql":{"type":"string","description":"仅允许单条 INSERT / UPDATE / DELETE"}},"required":["sql"]}
                    """,
            transports = {"MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER"})
    private Map<String, Object> executeDml(McpDbConnectionDO db, String sql) {
        validateDml(sql);
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            int affectedRows = ps.executeUpdate();
            return Map.of("affectedRows", affectedRows);
        } catch (SQLException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "SQL 执行失败: " + e.getMessage());
        }
    }

    private Map<String, Object> executePreparedUpdate(McpDbConnectionDO db, String sql, List<Object> params) {
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            int affectedRows = ps.executeUpdate();
            return Map.of("sql", sql, "affectedRows", affectedRows);
        } catch (SQLException e) {
            throw new BizException(BizErrorCode.MCP_REMOTE_ERROR, "SQL 执行失败: " + e.getMessage());
        }
    }

    private Connection open(McpDbConnectionDO db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), cipher.decrypt(db.getPasswordEnc()));
    }

    private List<McpDbSchemaVO.Column> columns(DatabaseMetaData meta, String catalog, String schema, String table) throws SQLException {
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet pk = meta.getPrimaryKeys(catalog, schema, table)) {
            while (pk.next()) {
                primaryKeys.add(pk.getString("COLUMN_NAME"));
            }
        }
        List<McpDbSchemaVO.Column> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                columns.add(McpDbSchemaVO.Column.builder()
                        .name(rs.getString("COLUMN_NAME"))
                        .type(rs.getString("TYPE_NAME"))
                        .remarks(rs.getString("REMARKS"))
                        .primaryKey(primaryKeys.contains(rs.getString("COLUMN_NAME")))
                        .nullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable)
                        .build());
            }
        }
        return columns;
    }

    private String effectiveSchema(McpDbConnectionDO db, Connection conn) throws SQLException {
        if (db.getSchemaName() != null && !db.getSchemaName().isBlank()) {
            return db.getSchemaName();
        }
        if ("POSTGRESQL".equalsIgnoreCase(db.getDbType())) {
            return "public";
        }
        return conn.getSchema();
    }

    private static final Pattern DANGEROUS_SQL = Pattern.compile(
            "\\b(update|delete|insert|drop|alter|truncate|create|grant|revoke|merge|replace|call|exec|execute|load_file|outfile|dumpfile|copy|for\\s+update)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DDL_SQL = Pattern.compile(
            "\\b(drop|alter|truncate|create|grant|revoke|merge|replace|call|exec|execute|load_file|outfile|dumpfile|copy)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static void validateReadOnlySelect(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "SQL 不能为空");
        }
        String normalized = sql.strip();
        if (!normalized.regionMatches(true, 0, "select", 0, 6)
                || (normalized.length() > 6 && Character.isLetterOrDigit(normalized.charAt(6)))) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "只允许单条 SELECT 查询");
        }
        if (normalized.contains(";") || normalized.contains("--") || normalized.contains("/*") || normalized.contains("*/")) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "SQL 禁止多语句和注释");
        }
        if (DANGEROUS_SQL.matcher(normalized).find()) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "SQL 包含非只读操作");
        }
    }

    private static void validateDml(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "SQL 不能为空");
        }
        String normalized = sql.strip();
        boolean allowed = normalized.regionMatches(true, 0, "insert", 0, 6)
                || normalized.regionMatches(true, 0, "update", 0, 6)
                || normalized.regionMatches(true, 0, "delete", 0, 6);
        if (!allowed) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "只允许单条 INSERT / UPDATE / DELETE");
        }
        if (normalized.contains(";") || normalized.contains("--") || normalized.contains("/*") || normalized.contains("*/")) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "SQL 禁止多语句和注释");
        }
        if (DDL_SQL.matcher(normalized).find()) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "SQL 包含高危操作");
        }
        if (normalized.regionMatches(true, 0, "delete", 0, 6)
                && !normalized.toLowerCase(java.util.Locale.ROOT).contains(" where ")) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "DELETE 必须带 WHERE");
        }
        if (normalized.regionMatches(true, 0, "update", 0, 6)
                && !normalized.toLowerCase(java.util.Locale.ROOT).contains(" where ")) {
            throw new BizException(BizErrorCode.SQL_FORBIDDEN, "UPDATE 必须带 WHERE");
        }
    }

    private void validateTableAndColumns(McpDbConnectionDO db, String table, List<String> columns) {
        McpDbSchemaVO schema = introspect(db);
        McpDbSchemaVO.Table matched = schema.getTables().stream()
                .filter(t -> t.getName().equalsIgnoreCase(table))
                .findFirst()
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "表不存在: " + table));
        List<String> existingColumns = matched.getColumns().stream().map(McpDbSchemaVO.Column::getName).toList();
        for (String column : columns) {
            if (column == null || !existingColumns.stream().anyMatch(c -> c.equalsIgnoreCase(column))) {
                throw new BizException(BizErrorCode.INVALID_ARGUMENT, "字段不存在: " + column);
            }
        }
    }

    private static String quoteIdentifier(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, "非法标识符: " + identifier);
        }
        return identifier;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, key + " 不能为空");
        }
        return s;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.parseInt(s);
        }
        return def;
    }

    private static Map<String, Object> objectMap(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new BizException(BizErrorCode.INVALID_ARGUMENT, key + " 不能为空");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(Objects.toString(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
