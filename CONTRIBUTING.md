# 贡献指南

感谢你愿意一起改进 `imawx-mcp-gateway`。

## 开发环境

1. 安装 JDK 25、Maven 3.9+、Node.js 22+、pnpm 9+。
2. 创建 MySQL 数据库 `imawx_mcp_gateway`。
3. 使用 `dev` profile 启动后端。
4. 使用 `pnpm dev` 启动前端。

## 分支和提交

- 提交尽量小而聚焦。
- 后端、前端、数据库结构变更尽量拆清楚。
- 涉及持久化结构变更时必须包含 SQL/DDL。
- 不提交密钥、生产端点、日志、IDE 文件和构建产物。

## 提交前检查

```bash
cd mcp-gateway
mvn -B -DskipTests package

cd ../mcp-web-ui
pnpm install
pnpm build:prod
```

## PR 说明

Pull Request 建议说明：

- 改了什么
- 为什么改
- 如何测试
- 是否影响数据库结构或部署
- 是否影响安全边界，尤其是数据库、云资产、SSH、CI/CD 相关 Provider

## 安全问题

请不要在公开 Issue 中披露漏洞细节。安全问题请参考 [安全说明](docs/SECURITY.md)。
