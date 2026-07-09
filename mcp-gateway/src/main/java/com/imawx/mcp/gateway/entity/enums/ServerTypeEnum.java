package com.imawx.mcp.gateway.entity.enums;

import com.imawx.mcp.gateway.common.dict.DictOption;
import com.imawx.mcp.gateway.common.dict.DictKeys;

import java.util.Arrays;
import java.util.List;

/**
 * 服务类型枚举(2026-07-06 重构:跟 transportType 一一对应,不做二次聚合)。
 *
 * <p>用户原话:"服务类型不准确,redis/es/mongodb 都被标记为数据库了" +
 * "服务类型应该和 mcp 添加的协议保持一致,而不是拍脑袋去改"。
 *
 * <p>之前版本把所有关系 DB 聚合成 SQL_DB、NoSQL 聚合成 NO_SQL_DB、KV 聚合成 KV_STORE、
 * 搜索聚合成 SEARCH_ENGINE —— 这种"业务语义分类"用户不接受,要求跟 mcp 添加 backend
 * 时的具体协议一一对应。
 *
 * <p>新方案 serverType 跟 transportType 1:1 对齐:
 * <ul>
 *   <li>EXTERNAL      —— MCP 协议族归一(HTTP / SSE / STDIO / STREAMABLE_HTTP)
 *       <br>mcp 添加时这 4 个是同一根模板"外部 MCP"下的子选项,前端选哪个用户视角
 *       都是"外部 MCP",日志页没必要再拆 3 种 chip</li>
 *   <li>MYSQL / POSTGRESQL / SQLSERVER / ORACLE —— 关系 DB 各自独立
 *       <br>mcp 添加时是 DB 根模板下的 4 个子选项,日志页要能看到具体是哪个 DB</li>
 *   <li>REDIS / KV_DATABASE —— KV 缓存</li>
 *   <li>MONGODB / ELASTICSEARCH —— NoSQL</li>
 *   <li>ALIYUN_DNS / ALIYUN_OSS / TENCENT_CLOUD —— 云服务</li>
 *   <li>OPENAPI —— Swagger / OpenAPI 网关</li>
 *   <li>SSH —— 远端 Shell</li>
 * </ul>
 *
 * <p>前端 {@code /api/sys/constants} 的 {@code serverType} key 返 DictOption list,
 * 加新枚举值后 logs 页 select 自动有新选项,不用改前端字典代码。
 */
public enum ServerTypeEnum {
    /** 外部 MCP 协议族归一(HTTP/SSE/STDIO/STREAMABLE_HTTP 都归这一类)。 */
    EXTERNAL("EXTERNAL", "外部 MCP"),
    /** 关系数据库 —— MySQL。 */
    MYSQL("MYSQL", "MySQL"),
    /** 关系数据库 —— PostgreSQL。 */
    POSTGRESQL("POSTGRESQL", "PostgreSQL"),
    /** 关系数据库 —— SQL Server。 */
    SQLSERVER("SQLSERVER", "SQL Server"),
    /** 关系数据库 —— Oracle。 */
    ORACLE("ORACLE", "Oracle"),
    /** KV 缓存 —— Redis。 */
    REDIS("REDIS", "Redis"),
    /** 通用 KV 数据库。 */
    KV_DATABASE("KV_DATABASE", "KV 数据库"),
    /** NoSQL 文档数据库 —— MongoDB。 */
    MONGODB("MONGODB", "MongoDB"),
    /** 搜索引擎 —— Elasticsearch。 */
    ELASTICSEARCH("ELASTICSEARCH", "Elasticsearch"),
    /** 阿里云域名 / DNS / CloudOps。 */
    ALIYUN_DNS("ALIYUN_DNS", "阿里云域名"),
    /** 阿里云对象存储 OSS。 */
    ALIYUN_OSS("ALIYUN_OSS", "阿里云 OSS"),
    /** 腾讯云资源 MCP。 */
    TENCENT_CLOUD("TENCENT_CLOUD", "腾讯云"),
    /** Swagger / OpenAPI 网关。 */
    OPENAPI("OPENAPI", "OpenAPI"),
    /** 远端 Shell。 */
    SSH("SSH", "SSH"),
    /** 2026-07-06 加:Drone CI/CD —— 查 build 进度 + 日志。 */
    DRONE("DRONE", "Drone");

    private final String code;
    private final String label;

    ServerTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static List<DictOption> asDictOptions() {
        return Arrays.stream(values())
                .map(e -> DictOption.of(e.code, e.label, e.name()))
                .toList();
    }

    public static ServerTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ServerTypeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }

    public static String dictKey() {
        return DictKeys.SERVER_TYPE;
    }
}
