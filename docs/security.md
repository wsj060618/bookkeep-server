# 上云前安全体检（2026-11-07）

> 范围：后端代码、认证链路、依赖版本、Nginx 反代配置、部署形态。
> 结论：**发现 5 个需要修的问题，已全部修复**；另记录 3 个「接受的风险」和 2 个「上线前人工确认项」。

## 一、已修复

| # | 问题 | 风险 | 处理 |
|---|---|---|---|
| 1 | 依赖版本过旧：Spring Boot 3.3.5（2024-11） | 2025–2026 的框架安全补丁全部缺失 | 升级 **3.5.16**；MyBatis-Plus 3.5.7 → **3.5.18**（分页拆出 `mybatis-plus-jsqlparser`）；jjwt 0.12.6 → **0.12.7**；全量 106+8 用例通过 |
| 2 | **登录/注册无任何限流** | 撞库、批量注册、CPU 打满（PLAN §9 第 1 条明确列为公网前置条件） | 新增 `AuthThrottle`（Caffeine）：同一 `IP+email` 失败 5 次锁 5 分钟、单 IP 30 次/分钟、单 IP 注册 5 次/小时；成功登录清零；严格值只在 `application-prod.yml` 生效 |
| 3 | `jwt.secret` 有仓库内公开默认值 | 忘记配环境变量 → 任何人可伪造任意 userId 的 token（最严重的一类） | `ProdSecurityGuard` 在 **prod profile 启动时自检**：用默认值或长度 <32 直接拒绝启动 |
| 4 | `LogAspect` 序列化失败时降级 `Arrays.toString(args)` | 极端情况下把 password 明文写进日志 | 降级为固定字符串，不再输出参数内容 |
| 5 | Nginx 无安全响应头 | 点击劫持、MIME 嗅探、referer 泄露、XSS 放大 | 本地与云端配置均加 `CSP`、`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`Referrer-Policy`、`Permissions-Policy`；云端再加 `HSTS`。注意 `add_header` 不继承，`/index.html` 块内重复声明 |

附带：`application-prod.yml` 把 token 有效期从 7 天缩到 **1 天**（`JWT_EXPIRE` 可覆盖），Nginx 对 `/api/auth/(login|register|forgot-password)` 再加一层 20 次/分钟粗限流（`limit_req`）。

## 二、检查过、确认没问题

| 项 | 为什么安全 |
|---|---|
| SQL 注入 | 全项目没有一处 `${}` 拼接；手写 SQL 也全是 `#{}` 参数化 |
| 越权（IDOR） | 所有账本/分类/流水查询都带 `user_id` 归属条件；越权一律 404，不泄露 id 是否存在 |
| CSRF | 无 Cookie 会话，纯 `Authorization: Bearer`；`csrf().disable()` 是合理的 |
| CORS | 未配置 = 默认同源；生产前后端同源由 Nginx 保证 |
| 密码存储 | BCrypt，接口与日志均不回传/不打印 |
| 重置密码 | token 只存 sha256、15 分钟、一次性、重新申请作废旧 token；接口永远返回同一句提示（防枚举） |
| 登录枚举 | 邮箱不存在与密码错误同一句「邮箱或密码错误」 |
| 错误信息泄露 | `GlobalExceptionHandler` 兜底只返回 `code=500`，堆栈只在服务端日志 |
| 后台/调试端点 | 未引入 Actuator、Swagger 等可探测端点 |
| 上传 | 目前没有任何文件上传接口（导入功能会按 §10.3 单独做大小/类型校验） |
| 账号注销 | 已是物理删除，不留墓碑数据 |

## 三、接受的风险（写清楚，不假装没有）

1. **邮箱未验证**：注册只校验格式，不发确认信。攻击者可以用别人的邮箱注册，但只能控制自己那个账号，改不了对方数据；真要根治需要邮箱验证流程，属于新需求。
2. **token 存 localStorage**：XSS 时可能被窃取；用 CSP 限制脚本来源 + Vue 默认转义来缓解。改成 httpOnly Cookie 需要引入 CSRF 方案，收益与成本不匹配。
3. **限流是单实例内存**：多副本会各算各的。本项目单机部署，不构成问题；换集群要换 Redis/网关限流。
4. **注册接口会提示「用户名已存在 / 邮箱已被注册」**：这是 UX 取舍，会暴露某个名字/邮箱是否注册过；被滥用时可改为统一提示。

## 四、上线前人工确认（部署侧，代码管不到）

1. 服务器只开 22/80/443；**3306、8080 不 publish**（`docker/docker-compose.yml` 已按此写）
2. MySQL 使用专用账号（compose 默认 `bookkeep`，仅授权本库），不用 root
3. 全站 HTTPS + HTTP 301 + HSTS；证书路径见 `docker/README.md`
4. `JWT_SECRET`、数据库密码、邮箱授权码全部来自 `.env` / `deploy/env.ps1`，不进仓库
5. 备份恢复演练一次（dump → 新库 → 核对行数与页面）
6. 可选：上线前跑一次 `mvn org.owasp:dependency-check-maven:check`（需要下载 NVD 库，较慢）

## 五、复现这份结论

```powershell
mvn -pl bookkeep-web -am test      # 106 + 8 用例
# 限流单独验证
mvn -pl bookkeep-web -am test -Dtest=AuthThrottleTest
```