# 扩展内置 Provider

内置 Provider 用来把内部系统直接暴露成 MCP Tool，不需要额外运行一个 STDIO 进程。适合数据库、Redis、云服务、CI/CD、SSH、内部 HTTP API 这类团队已有资产。

## 开发清单

1. 在 `service/mcpproxy/provider/...` 下新增或修改 Provider。
2. 实现统一 Provider 接口。
3. Tool 方法使用 `@McpToolDefinition` 标注工具名、描述和参数结构。
4. 在 `resources/mcp/builtin-templates.json` 增加前端创建模板。
5. 在 `resources/mcp/builtin-probes.json` 增加默认探活工具。
6. 在 `resources/mcp/builtin-transports.json` 增加必填字段和校验规则。
7. 非密钥配置写入 `mcp_backend_extension.config_json`。
8. 密钥配置写入 `mcp_backend_extension.secret_enc`。
9. 后端校验必填字段、资源范围和安全约束。
10. 验证 `tools/list` 和 `tools/call`。

## 命名建议

- Transport type 使用稳定的大写标识，例如 `MYSQL`、`REDIS`、`ALIYUN_OSS`、`DRONE`。
- Tool 名称短而明确，并带上 Provider 前缀，例如 `redis_get`、`oss_list_objects`、`drone_get_latest_build`。
- Tool 描述要写明目标资源、动作和重要限制，方便大模型选择正确工具。

## 资源范围

凡是能读取敏感数据或执行变更操作的 Provider，都应该支持资源范围配置，例如：

- 数据库 schema/table 白名单
- Redis database 白名单、默认 DB、key prefix、只读模式
- OSS bucket 白名单
- DNS domain 白名单
- SSH 命令白名单
- Drone 仓库范围

## 审计要求

Provider 调用必须写入 `mcp_call_log`，至少包含：

- 真实 `mcp_backend.id`
- `mcp_backend.backend_id`
- MCP 实例名称快照
- Tool 名称
- Tool 描述快照
- 入参
- 结果
- stream logs
- 客户端 IP 和 User-Agent

不要用临时或合成 backend id 写审计日志，否则实例删除后日志会失去定位能力。

## 工具定义建议

当前内置工具元数据由 `@McpToolDefinition` 注解扫描并加载到内存。新增工具时优先修改注解定义，避免再维护一份手写 tool 列表；如果确实需要 fallback，请保证名称、描述、JSON Schema 与注解完全一致。

对于会改变远端状态的工具，请在 provider 内集中定义写操作集合，并统一处理只读模式、资源范围和危险参数校验。
