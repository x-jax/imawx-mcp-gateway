package com.imawx.mcp.gateway.common.enums;

import com.imawx.mcp.gateway.common.dict.DictKeys;
import com.imawx.mcp.gateway.common.dict.DictOption;

import java.util.List;

/** 动态数据库连接类型。 */
public enum DbTypeEnum {
    MYSQL("MYSQL", "MySQL", 3306),
    POSTGRESQL("POSTGRESQL", "PostgreSQL", 5432),
    ORACLE("ORACLE", "Oracle", 1521),
    SQLSERVER("SQLSERVER", "SQL Server", 1433);

    private final String code;
    private final String label;
    private final int defaultPort;

    DbTypeEnum(String code, String label, int defaultPort) {
        this.code = code;
        this.label = label;
        this.defaultPort = defaultPort;
    }

    public String getCode() {
        return code;
    }

    public static DbTypeEnum fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("dbType 不能为空");
        }
        for (DbTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的 dbType: " + code);
    }

    public static List<DictOption> asDictOptions() {
        return List.of(
                option(MYSQL),
                option(POSTGRESQL),
                option(ORACLE),
                option(SQLSERVER)
        );
    }

    public static String dictKey() {
        return DictKeys.DB_TYPE;
    }

    private static DictOption option(DbTypeEnum type) {
        return DictOption.of(type.code, type.label, type.name(), java.util.Map.of("defaultPort", type.defaultPort));
    }
}
