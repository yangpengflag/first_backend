# WanderChina Backend

Spring Boot 3.5.16 / Java 17 后端服务。规格文档见 `openspec/specs/auth-module/spec.md`。

## 本地运行

> ⚠️ **本机环境注意**：`mvn` 未加入 PATH，且默认会走到 JDK 8（`C:\Program Files\Java\jdk1.8.0_152`），
> 而 Spring Boot 3.5.16 要求 Java 17。直接执行 `mvn` 会报
> `类文件具有错误的版本 61.0, 应为 52.0`。请显式指定 `JAVA_HOME`。

```powershell
# Windows PowerShell
$env:JAVA_HOME = "D:\Programs\java17"
& "D:\Programs\maven\bin\mvn.cmd" spring-boot:run     # 启动服务（8080）
& "D:\Programs\maven\bin\mvn.cmd" test                # 运行测试
```

Linux / macOS 无需额外设置：

```bash
./mvnw spring-boot:run   # 或 mvn spring-boot:run
```

---

## Auth 模块

### 端点

| 方法 | 路径 | 鉴权 | 成功 | 主要错误 |
|---|---|---|---|---|
| POST | `/api/auth/register` | 免 | `201` + `UserResponse` | `400` / `409` / `429` |
| POST | `/api/auth/login` | 免 | `200` + tokens | `401` / `403` / `423` / `429` |
| GET | `/api/auth/verify?code=` | 免 | `200` | `400` |
| POST | `/api/auth/resend-verification` | 免 | `202` | `429` |
| POST | `/api/auth/refresh` | 免 | `200` | `401` / `423` |
| POST | `/api/auth/logout` | 需 | `204` | `401` |
| GET | `/api/auth/me` | 需 | `200` | `401` / `423` |
| DELETE | `/api/auth/me` | 需 | `204` | `401` |

### 用户状态机

| 状态 | HTTP | error.code | 触发 |
|---|---|---|---|
| `ACTIVE` | `200` | — | 邮箱验证完成后 |
| `LOCKED` | `423` | `ACCOUNT_LOCKED` | 连续失败 5 次（锁 15 分钟，自动解除） |
| `DELETED` | `401` | `ACCOUNT_DELETED` | 用户注销（软删除，不可逆） |
| `EMAIL_UNVERIFIED` | `403` | `EMAIL_NOT_VERIFIED` | 注册后未验证邮箱 |

登录判定顺序：用户不存在 → `401`；`DELETED` → `401`；`LOCKED` → `423`；
锁定期届满 → 自动解锁继续；密码错误 → `401`（计数 +1）；`EMAIL_UNVERIFIED` → `403`；成功 → `200`。

**邮箱验证是免鉴权旁路**（`GET /api/auth/verify`），用于破解「未验证用户被 403 挡住、却又无法登录去触发验证」的死锁。
重发验证邮件恒定返回 `202`，不泄露邮箱是否已注册。

### 过滤链顺序

```
请求 → RateLimitFilter → JwtAuthFilter → UserStatusFilter → DispatcherServlet
```

`UserStatusFilter` 每个请求回查用户当前状态，因此**锁定与注销即时生效**——
令牌即使尚未过期也不再放行。这是 JWT 无状态与「即时吊销」张力的解法。

### 限流

| 端点 | IP 维度 | (IP + email) 维度 |
|---|---|---|
| `POST /register` | 5 次 / 1 小时 | — |
| `POST /login` | 10 次 / 15 分钟 | 5 次 / 15 分钟 |
| `POST /resend-verification` | 10 次 / 1 小时 | 3 次 / 24 小时 |

超限返回 `429 RATE_LIMITED`，且**不执行密码校验**（不产生失败计数副作用）。

### 安全边界

- `UserResponse` 是**唯一**允许出网的用户表示（白名单 DTO）。
  `User` 实体**禁止**作为 Controller 返回值。
- 禁止出网字段：`passwordHash` / `salt` / `verificationCode`（及其 snake_case 形式）。
- 护栏：`UserResponseSerializationTest` 断言序列化**键集合严格等于白名单**，
  因此新增实体字段默认不可见，必须显式加入 DTO 才会输出。
- 密码以 BCrypt（strength 10）存储，salt 内嵌于散列值，不设独立 salt 列。
- 验证码属一次性凭证，默认**不写入日志**（`auth.mail.log-verification-code`，默认 `false`）。

### MVP 已知限制

| 限制 | 说明 | 后续 |
|---|---|---|
| H2 文件模式（`./data/wanderchina`） | 单机可用，非生产数据库 | 切换 PostgreSQL 仅需改 `application.yml` 数据源 |
| 邮件双实现：日志 / SMTP | 默认 `LoggingMailSender`（仅日志）；配置 `spring.mail.host` 后自动切换为 `SmtpMailSender` 真发 | 见下方「启用真实邮件（SMTP）」 |
| 限流为内存实现 | 多实例部署时各实例独立计数 | 横向扩展时需替换为 Redis |
| 登出为客户端丢弃令牌 | 服务端不维护黑名单，access token 在 15 分钟内仍有效 | 状态过滤已覆盖锁定/注销，剩余窗口可接受 |

### 配置

关键配置见 `src/main/resources/application.yml`：

| 配置 | 默认 | 说明 |
|---|---|---|
| `auth.jwt.secret` | 开发默认值 | **生产必须通过 `JWT_SECRET` 环境变量注入** |
| `auth.jwt.access-token-ttl-minutes` | `15` | access token 有效期 |
| `auth.jwt.refresh-token-ttl-days` | `7` | refresh token 有效期 |
| `auth.bcrypt.strength` | `10` | BCrypt 强度 |
| `auth.max-failed-attempts` | `5` | 锁定阈值 |
| `auth.lock-duration-minutes` | `15` | 锁定时长 |
| `auth.mail.log-verification-code` | `false` | 是否在日志中打印验证码 |

#### 启用真实邮件（SMTP）

默认 `spring.mail.host` 未配置，`MailSender` 由 `LoggingMailSender` 实现（仅把验证 / 重置链接打到控制台日志），
无需任何 SMTP 凭据，适合本地联调。

要真正发信，**只需注入环境变量**，`SmtpMailSender` 会自动接管、`LoggingMailSender` 退出：

```bash
# 以 QQ 邮箱为例（smtp.qq.com，SSL 465）；host/port/TLS 已在 application.yml 固化，只需提供凭据
export SPRING_MAIL_HOST=smtp.qq.com
export SPRING_MAIL_USERNAME=你的完整QQ邮箱
export SPRING_MAIL_PASSWORD=<QQ 授权码>   # 邮箱设置→账户→开启 IMAP/SMTP 后生成的授权码，不是登录密码
```

##### 配置分层原则（上线必读）

- **非敏感项（可提交仓库）**：`host` / `port` / TLS 参数已写在 `application.yml`，属公开信息，可安全入库。
- **敏感项（禁止入库）**：`SPRING_MAIL_PASSWORD`（QQ 授权码）等凭据**绝不写进 yml / 源码 / 镜像**，只经环境变量或密钥管理注入：
  - 本地：`.env`（`已被 .gitignore 忽略`）或 shell `export`；
  - Docker：`docker run -e SPRING_MAIL_PASSWORD=...` 或 compose `env_file: .env`（`.env` 不提交）；
  - K8s / 云：`Secret` + `envFrom: secretRef`，CI/CD 仅在部署阶段注入。
- **激活判定**：仅当 `SPRING_MAIL_HOST` 被设置，`SmtpMailSender` 才接管；否则回退日志实现——缺失凭据时不会把发信失败暴露成注册异常。

##### 日志开关

`auth.mail.log-verification-code` 默认 `false`（生产安全，一次性码不落日志）。
本地调试需在日志看到完整链接时，用 dev profile 开启（见 `application-dev.yml`）：

```bash
SPRING_PROFILES_ACTIVE=dev java -jar backend.jar   # 或 ./gradlew bootRun --args='--spring.profiles.active=dev'
```

`from` 默认取 `spring.mail.username`（QQ 要求发件人等于已认证账号）。前端跳转链接由 `app.frontend-base-url` 决定；
**生产务必通过环境变量 `FRONTEND_BASE_URL` 改为线上域名**，切勿保留 `http://localhost:3000`。

### 按环境加载配置（.env.dev / .env.prod）

本项目用 `.env.<profile>` 存放环境变量（含敏感凭据），**不提交仓库**（已被 `.gitignore` 忽略）。
仓库内仅保留 `.env.example` 作为模板。

| 文件 | 用途 | 入库 |
|---|---|---|
| `.env.dev` | 本地开发：QQ 邮箱凭据、本地 DB、localhost 前端 | ❌ 忽略 |
| `.env.prod` | 生产：真实域名、生产 DB、强 JWT 密钥 | ❌ 忽略 |
| `.env.example` | 变量清单模板（无真实凭据） | ✅ 入库 |

非敏感的环境差异化配置放 `application-dev.yml` / `application-prod.yml`（如日志开关、是否关闭文档）；
**凭据一律来自 `.env.<profile>`**，yml 不写明文。

激活方式（任选其一）：

- **脚本（推荐）**：仓库根目录执行 `./run.sh dev`（或 Windows 用 `.\run.ps1 dev`）；prod 同理。脚本读取 `.env.dev` 注入环境变量，并以 `SPRING_PROFILES_ACTIVE=dev` 启动 `mvn spring-boot:run`。
- **IDE（IntelliJ）**：Run/Debug 配置中设 `Active profiles = dev`，并在 `Environment variables` 粘贴 `.env.dev` 内容（或装 EnvFile 插件直接加载）。
- **手动**：`export $(grep -v '^#' .env.dev | xargs) && SPRING_PROFILES_ACTIVE=dev mvn -f backend spring-boot:run`
