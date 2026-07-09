package com.imawx.mcp.gateway.common.dict;

/**
 * 前端常量字典的统一 key 常量(2026-07-01 重构)。
 *
 * <p>前后端契约:key 字符串完全对齐,前端 {@code useConstants().getOptions(key)}
 * 用同名 key 读。后端任何改 key 名的地方都得同步改前端。
 *
 * <p>key 命名规则:业务模块_字典名(snake_case),跟 MySQL 表前缀/模块名对齐。
 *
 * <p>枚举参考文档:这些 key 对应的"全集"在哪定义:
 * <ul>
 *   <li>{@code loginMethods} → {@code com.imawx.mcp.gateway.entity.enums.LoginMethodEnum}</li>
 *   <li>{@code userStatus} → {@code com.imawx.mcp.gateway.entity.enums.UserStatusEnum}</li>
 *   <li>{@code protocol} → {@code com.imawx.mcp.gateway.common.enums.TransportType}</li>
 *   <li>{@code connectionStatus} → {@code com.imawx.mcp.gateway.entity.enums.ConnectionStatusEnum}</li>
 *   <li>{@code invokeStatus} → {@code com.imawx.mcp.gateway.entity.enums.InvokeStatusEnum}</li>
 *   <li>{@code serverType} → {@code com.imawx.mcp.gateway.entity.enums.ServerTypeEnum}</li>
 * </ul>
 *
 * @author Mavis
 * @since 2026-07-01
 */
public final class DictKeys {

    /** 登录方式:password / sms / oauth / sso 等。 */
    public static final String LOGIN_METHODS = "loginMethods";

    /** 用户状态:1启用 / 0禁用。 */
    public static final String USER_STATUS = "userStatus";

    /** MCP 传输协议:HTTP / SSE / STDIO(STREAMABLE_HTTP 后端枚举保留,前端不暴露)。 */
    public static final String PROTOCOL = "protocol";

    /** 动态数据库类型:MYSQL / POSTGRESQL / ORACLE / SQLSERVER。 */
    public static final String DB_TYPE = "dbType";

    /** MCP 连接状态:DISCONNECTED / CONNECTED / FAILED。 */
    public static final String CONNECTION_STATUS = "connectionStatus";

    /** 调用日志状态:SUCCESS / FAILED / TIMEOUT。 */
    public static final String INVOKE_STATUS = "invokeStatus";

    /** 服务类型:EXTERNAL / DB / ALIYUN_DNS / ALIYUN_OSS / TENCENT_CLOUD / REDIS / KV_DATABASE。 */
    public static final String SERVER_TYPE = "serverType";

    private DictKeys() {
    }
}
