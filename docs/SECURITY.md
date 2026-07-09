# 安全说明

`imawx-mcp-gateway` 可能连接数据库、云资产、SSH 主机、CI/CD 系统和内部 API。一旦网关被攻破，攻击者可能通过 MCP 工具继续横向操作内部资源。因此需要把网关当作高权限基础设施来部署和维护。

## 生产要求

- 必须运行在 HTTPS 后面。
- 浏览器管理接口必须启用 CSRF。
- Session Cookie 应启用 `HttpOnly`、`Secure`、`SameSite`。
- 每个 MCP 实例使用最小权限凭证。
- API Token 限定到必要的 MCP 实例和 Tool。
- 定期轮换 API Token 和后端凭证。
- 保持调用日志和访问日志开启。
- 给调用日志、应用日志配置敏感字段隐藏规则。
- `/ws/**` 日志订阅必须要求登录态。
- Actuator 不应暴露 `health`、`info` 以外的端点。

## 密钥处理

- 不提交真实数据库密码、云密钥、SSH 私钥、API Token 和生产域名私密配置。
- MCP 实例密钥写入 `mcp_backend_extension.secret_enc`。
- TOTP 密钥加密存储。
- RSA-OAEP 私钥通过 `MCP_GATEWAY_SECURITY_TOTP_KEY_FILE` 或密钥管理系统提供。
- 云服务优先使用最小权限 RAM/IAM 用户，不使用主账号密钥。

## Provider 安全建议

数据库：

- 只读场景优先使用只读账号。
- 读写能力拆成不同 MCP 实例。
- 尽量配置 schema、table、SQL 操作范围。

NoSQL：

- Redis、MongoDB、Elasticsearch 使用独立账号或资源前缀。
- 避免把生产 root 权限直接暴露给 MCP。

SSH：

- 优先配置允许命令列表。
- 允许命令为空代表不限制执行命令，生产环境需要谨慎启用。
- 优先使用受限系统用户和密钥登录。

云服务：

- OSS 建议限制 bucket 范围。
- DNS 建议限制域名范围。
- 云侧权限策略应与 MCP 实例配置保持一致。

## 漏洞反馈

请不要在公开 Issue 中贴出 Token、日志、攻击步骤或包含生产数据的截图。安全问题应私下反馈给维护者。
