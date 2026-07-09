# 架构说明

项目由两个应用组成：

- `mcp-gateway`：Spring Boot 后端，负责 MCP 协议入口、Provider 调度、鉴权、审计和监控。
- `mcp-web-ui`：Vue 管理后台，负责 MCP 实例、Token、日志、系统配置和用户管理。

## 后端分层

```text
controller/      Web API、MCP 协议入口、管理端接口
service/         业务服务、Provider 调度、审计日志、认证、监控
provider/        内置 MCP Provider 实现
mapper/          MyBatis-Plus Mapper 和 XML SQL
entity/          DO、DTO、VO、枚举
resources/db/    MyBatis-Plus DDL 初始化 SQL
resources/mcp/   内置 Provider 模板、探活工具、协议描述
```

## MCP 调用链路

1. 客户端请求 `POST /mcp`。
2. 网关使用 Bearer Token 完成认证。
3. `tools/list` 根据 Token 授权范围返回可见工具。
4. `tools/call` 解析工具名中的实例前缀，并定位真实 MCP 实例。
5. 网关根据实例类型路由到外部 MCP 或内置 Provider。
6. 调用结果、入参、stream logs、耗时和状态写入 `mcp_call_log`。

## 管理后台链路

1. 浏览器用户通过 Session 登录。
2. 生产环境对状态变更接口启用 CSRF。
3. 后台管理 MCP 实例、Token、日志、用户和系统配置。
4. API 访问写入 `mcp_access_log`，用于运维审计。

## 内置 Provider 模型

内置 Provider 使用统一模式：

- Provider 声明支持的 transport type 和工具集合。
- Tool 方法使用 `@McpToolDefinition` 标注名称、描述和参数结构。
- `builtin-templates.json` 提供前端创建表单模板。
- `builtin-probes.json` 声明默认探活工具。
- `builtin-transports.json` 声明必填字段、端点规则和密钥字段。
- 普通配置写入 `mcp_backend_extension.config_json`。
- 密钥配置写入 `mcp_backend_extension.secret_enc`。

这样新增 Provider 时不需要在 Controller 或前端页面里写死工具列表。
