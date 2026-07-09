# 部署文档

本文档描述单机生产部署方式，适合一台服务器同时运行前端静态资源、后端应用、Nginx 和 MySQL 的场景。多节点部署时，需要额外规划共享 Session、日志、密钥、对象存储和数据库连接池。

## 部署目标

推荐目录如下：

```text
/app/imawx-mcp-gateway/
├── app/
│   ├── mcp-gateway.jar
│   └── logs/
└── dist/
    ├── index.html
    └── assets/
```

约定：

- 前端部署目录：`/app/imawx-mcp-gateway/dist/`
- 后端部署目录：`/app/imawx-mcp-gateway/app/`
- 后端 Jar：`/app/imawx-mcp-gateway/app/mcp-gateway.jar`
- 后端日志目录：`/app/imawx-mcp-gateway/app/logs/`
- 标准 MCP 入口：`https://gateway.example.com/mcp`
- 管理后台 API：`https://gateway.example.com/api/`
- 日志 WebSocket：`wss://gateway.example.com/ws/`

## 构建产物

后端：

```bash
cd mcp-gateway
mvn -B -DskipTests clean package
```

产物：

```text
mcp-gateway/target/mcp-gateway.jar
```

前端：

```bash
cd mcp-web-ui
pnpm install --frozen-lockfile
pnpm build:prod
```

产物：

```text
mcp-web-ui/dist/
```

## 私有配置

仓库只提交示例配置，不提交真实开发或生产配置。

开发环境：

```bash
cp mcp-gateway/src/main/resources/application-localdev.example.yml \
   mcp-gateway/src/main/resources/application-localdev.yml
SPRING_PROFILES_ACTIVE=dev,localdev mvn spring-boot:run
```

生产私有覆盖：

```bash
cp mcp-gateway/src/main/resources/application-localprod.example.yml \
   mcp-gateway/src/main/resources/application-localprod.yml
java -jar mcp-gateway.jar --spring.profiles.active=prod,localprod
```

这些文件已经加入 `.gitignore`：

```text
application-localdev.yml
application-localprod.yml
application-*.local.yml
```

## 数据库准备

创建数据库：

```sql
CREATE DATABASE imawx_mcp_gateway
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

建议生产环境使用独立数据库账号，并只授予当前库权限：

```sql
CREATE USER 'mcp_gateway'@'%' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON imawx_mcp_gateway.* TO 'mcp_gateway'@'%';
FLUSH PRIVILEGES;
```

表结构由 MyBatis-Plus DDL 初始化。首次启动会扫描 `mcp-gateway/src/main/resources/db/`，当前开源基线收敛为一个 `V20260704_001__init.sql`。

## 加密密钥

敏感字段使用 RSA-OAEP 加密。生产环境必须准备 PKCS#8 私钥：

```bash
mkdir -p /etc/imawx-mcp-gateway
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 \
  -out /etc/imawx-mcp-gateway/totp.key
chmod 600 /etc/imawx-mcp-gateway/totp.key
```

该私钥用于加密 TOTP secret 和后端凭证等敏感字段。私钥丢失后，数据库中已加密的敏感字段无法解密。

## 环境变量

创建 `/etc/imawx-mcp-gateway/gateway.env`：

```bash
# Spring profile
SPRING_PROFILES_ACTIVE=prod

# MySQL
MCP_GATEWAY_DATABASE_HOST=127.0.0.1:3306
MCP_GATEWAY_DATABASE_USERNAME=mcp_gateway
MCP_GATEWAY_DATABASE_PASSWORD=change-me

# RSA-OAEP private key for encrypted TOTP/backend secrets
MCP_GATEWAY_SECURITY_TOTP_KEY_FILE=/etc/imawx-mcp-gateway/totp.key

# Console origin. Replace with your production HTTPS domain.
MCP_GATEWAY_WEB_CORS_ALLOWED_ORIGIN_PATTERNS=https://gateway.example.com

# Application log directory
MCP_GATEWAY_LOG_DIR=/app/imawx-mcp-gateway/app/logs

# Reverse proxy CIDR allowlist used when parsing forwarded headers.
# Keep localhost when Nginx and backend run on the same machine.
MCP_GATEWAY_SECURITY_TRUSTED_PROXY_CIDRS=127.0.0.1/32,::1/128
```

保护环境变量文件：

```bash
chmod 600 /etc/imawx-mcp-gateway/gateway.env
```

生产 profile 默认启用：

- HTTPS 强制校验
- CSRF
- Secure Session Cookie
- TOTP 总开关
- 日志目录 `/app/imawx-mcp-gateway/app/logs`

## 安装后端服务

创建目录并复制 Jar：

```bash
mkdir -p /app/imawx-mcp-gateway/app/logs
cp mcp-gateway/target/mcp-gateway.jar /app/imawx-mcp-gateway/app/mcp-gateway.jar
```

创建 `/etc/systemd/system/imawx-mcp-gateway.service`。当前生产使用的 service 配置如下，敏感信息统一放在 `EnvironmentFile`，service 文件本身不包含数据库密码或密钥：

```ini
[Unit]
Description=imawx MCP Gateway
After=network.target

[Service]
Type=simple
WorkingDirectory=/app/imawx-mcp-gateway/app
EnvironmentFile=/etc/imawx-mcp-gateway/gateway.env
ExecStart=/usr/bin/java -jar /app/imawx-mcp-gateway/app/mcp-gateway.jar
Restart=always
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

启动服务：

```bash
systemctl daemon-reload
systemctl enable imawx-mcp-gateway.service
systemctl start imawx-mcp-gateway.service
systemctl status imawx-mcp-gateway.service --no-pager
```

查看日志：

```bash
journalctl -u imawx-mcp-gateway.service -f
tail -f /app/imawx-mcp-gateway/app/logs/mcp-gateway.log
```

## 首次管理员

生产 profile 第一次启动时，系统会创建管理员账号：

```text
admin@imawx.local
```

同时会在日志目录写入一次性 bootstrap 文件：

```text
/app/imawx-mcp-gateway/app/logs/bootstrap-admin-*.txt
```

文件包含：

- `login_email`
- `initial_password`
- `totp_secret_base32`
- `totp_otpauth_uri`
- `totp_current_code`
- `totp_current_code_valid_until_epoch`

读取方式：

```bash
ls -lah /app/imawx-mcp-gateway/app/logs/bootstrap-admin-*.txt
cat /app/imawx-mcp-gateway/app/logs/bootstrap-admin-*.txt
```

首次登录后请立即：

1. 修改管理员密码。
2. 重新绑定或确认 TOTP。
3. 删除 bootstrap 文件。

```bash
rm -f /app/imawx-mcp-gateway/app/logs/bootstrap-admin-*.txt
```

如果管理员已存在并且 TOTP secret 不为空，后续重启不会重新生成 bootstrap 文件。

## 部署前端

```bash
mkdir -p /app/imawx-mcp-gateway/dist
rm -rf /app/imawx-mcp-gateway/dist/*
cp -a mcp-web-ui/dist/. /app/imawx-mcp-gateway/dist/
```

确认：

```bash
test -f /app/imawx-mcp-gateway/dist/index.html
ls -lah /app/imawx-mcp-gateway/dist/assets | head
```

## Nginx 配置

推荐前后端同域部署。关键点：

- `/` 走前端 SPA fallback。
- `/api/` 代理后端管理接口。
- `location = /mcp` 只匹配标准 MCP 固定入口，避免 `/mcp-proxy` 页面刷新时被代理到后端。
- `/ws/` 代理日志 WebSocket。

示例：

```nginx
server {
    listen 443 ssl;
    server_name gateway.example.com;

    ssl_certificate /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    root /app/imawx-mcp-gateway/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 60s;
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }

    location = /mcp {
        proxy_pass http://127.0.0.1:8080/mcp;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 60s;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 3600s;
    }
}
```

如果 Nginx 没有注册到 systemd，而是安装在 `/usr/local/nginx/`，使用：

```bash
/usr/local/nginx/sbin/nginx -t
/usr/local/nginx/sbin/nginx -s reload
```

如果已经注册 systemd，则使用：

```bash
nginx -t
systemctl reload nginx
```

## Drone CI/CD

仓库内 `.drone.yml` 提供了一个生产构建部署示例。

### 运行环境

当前流水线按 x86_64 Drone Runner 设计：

```yaml
platform:
  os: linux
  arch: amd64
```

后端构建镜像：

```text
maven:3.9.11-eclipse-temurin-25
```

前端构建镜像：

```text
node:22-alpine
```

### 缓存

Maven 缓存：

```text
/tmp/drone-cache/imawx-mcp-gateway/m2
```

pnpm 缓存：

```text
/tmp/drone-cache/imawx-mcp-gateway/pnpm-store
```

Maven 构建时会写入阿里云公共仓库镜像：

```xml
<mirror>
  <id>aliyunmaven</id>
  <mirrorOf>*</mirrorOf>
  <name>Aliyun Maven</name>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

### Secret

Drone 需要配置：

```text
MCP_GATEWAY_PROD_HOST  # 生产服务器 IP 或域名
MCP_GATEWAY_PROD_PWD   # 服务器 SSH 密码
```

这两个值必须通过 Drone Secret 注入，不要写入仓库。

### 部署行为

流水线会：

1. 构建后端 Jar。
2. 构建前端 `dist`。
3. 打包 `deploy/frontend/dist` 和 `deploy/backend/mcp-gateway.jar`。
4. 上传到生产服务器 `/tmp/imawx-mcp-gateway-${DRONE_BUILD_NUMBER}`。
5. 覆盖 `/app/imawx-mcp-gateway/dist/`。
6. 覆盖 `/app/imawx-mcp-gateway/app/mcp-gateway.jar`。
7. 执行：

```bash
systemctl daemon-reload
systemctl enable imawx-mcp-gateway.service
systemctl restart imawx-mcp-gateway.service
systemctl --no-pager --full status imawx-mcp-gateway.service
```

流水线默认不修改 Nginx 配置。只有 Nginx 配置变更时才需要人工执行 reload。

## 发布后检查

后端：

```bash
systemctl status imawx-mcp-gateway.service --no-pager
journalctl -u imawx-mcp-gateway.service -n 200 --no-pager
curl -k https://gateway.example.com/api/meta/version
```

前端：

```bash
curl -I https://gateway.example.com/
curl -I https://gateway.example.com/assets/
```

MCP：

```bash
curl -sS https://gateway.example.com/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <your-token>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

WebSocket：

```bash
curl -I https://gateway.example.com/ws/log-files
```

浏览器检查：

- 刷新 `/dashboard` 不应 404。
- 刷新 `/mcp-proxy` 应仍然返回前端页面。
- 登录后刷新页面不应丢 Session。
- 日志文件页面应通过 WebSocket 正常订阅。

## 回滚

建议每次部署前在服务器保留上一版：

```bash
cp /app/imawx-mcp-gateway/app/mcp-gateway.jar \
   /app/imawx-mcp-gateway/app/mcp-gateway.jar.bak
tar -C /app/imawx-mcp-gateway -czf /app/imawx-mcp-gateway/dist.bak.tar.gz dist
```

回滚：

```bash
cp /app/imawx-mcp-gateway/app/mcp-gateway.jar.bak \
   /app/imawx-mcp-gateway/app/mcp-gateway.jar
rm -rf /app/imawx-mcp-gateway/dist
tar -C /app/imawx-mcp-gateway -xzf /app/imawx-mcp-gateway/dist.bak.tar.gz
systemctl restart imawx-mcp-gateway.service
```

## 常见问题

### 刷新 `/mcp-proxy` 直接打到后端

Nginx 不要写成 `location /mcp` 或 `location ^~ /mcp`。这会把 `/mcp-proxy` 也代理到后端。

应使用：

```nginx
location = /mcp {
    proxy_pass http://127.0.0.1:8080/mcp;
}
```

### 登录后刷新又要求登录

检查：

- `spring-session` 表是否创建成功。
- Nginx 是否传递 `X-Forwarded-Proto https`。
- 生产 Cookie 是否为 Secure。
- 浏览器请求是否同域。

### 日志 WebSocket 连接失败

检查：

- `/ws/` location 是否设置 `Upgrade` 和 `Connection`。
- 后端是否已登录态访问。
- Nginx `proxy_read_timeout` 是否足够长。

### 生产重复生成管理员 bootstrap

只有 `mcp_user.id=1` 不存在，或管理员 TOTP secret 为空时才会生成 bootstrap 文件。检查生产数据库是否被清空、连接到了错误数据库，或管理员记录是否异常。
