# 记账网站后端开发计划书（bookkeep-server）

## 1. 项目信息

- 后端位置：`D:\coding\bookkeep-server`（本人开发）
- 前端位置：`D:\coding\bookkeep-web`（**由 AI 编写**，本人负责契约定义与联调验收；严格按本计划书接口契约，硬约定见 §5.0，配合节奏见 §6.1）
  - 技术栈（第 7 天定）：Vue 3 + **TypeScript** + Vite + Element Plus + Pinia + vue-router + axios
  - 选 TS 的理由：接口字段写错在 `npm run build`（`vue-tsc`）就报错，而不是等联调；代价是本人要先过一遍 TS 基本概念（`bookkeep-web/README.md` 有速查）
  - 独立 git 仓库；`src/types/api.ts` 是后端 DTO 的镜像，**后端改 DTO 必须先改它**
- 技术栈：
  - Spring Boot 3.3.x + JDK 17 + Maven
  - MyBatis-Plus（`mybatis-plus-spring-boot3-starter`）+ MySQL 8
  - Lombok + spring-boot-starter-validation
  - Spring Security + JWT（jjwt 0.12.x）
- 工程结构：**Maven 父子工程（多模块）**，父工程 `bookkeep-server` 下拆四个子模块（`common` / `model` / `service` / `web`，见 §4）
- 部署形态（第 7 天定稿，一套代码两种部署目标，差异只在配置）：
  - **本地单机（日常使用，主目标）**：Windows + 本机 MySQL 8 + Nginx for Windows 托管前端静态文件，
    并反代 `/api` → `127.0.0.1:8080`；后端只监听回环地址（不对外端口），Nginx 监听 80（局域网可访问）
  - **云端（演示与取证，按需开启）**：Docker Compose 编排 `mysql` + `app` + `nginx`，部署到轻量应用服务器；
    **只在试用期 / 面试期开启**，不做长期租用（理由与成本见 §9）
  - 配置差异：本地 `application-local.yml`（gitignore），云端走环境变量；`nginx.conf` 两边共用，只改 `root` 与 `proxy_pass`
- 部署边界：**不做**前后端两个端口直连（会引入 CORS），一律「Nginx 同源托管 + 反代」，后端只出 JSON

## 2. 核心设计

> **场景定稿（第 7 天）**：这是一个给**生活委员记班费**的自用系统，单用户、单账本。
> 原计划里的个人版 / 商家版、多账本、预算、AI 分析、头像上传全部**有意砍掉**（原因见 §6 的范围取舍），
> 目的是一个月内做出「能真实用的系统 + 能讲的简历项目」。

### 2.1 多账本（类型仍只有 class）
- `book.type` 固定 `class`；一个用户可有多本账本（上限 10 本），同一时刻只有一本是**当前账本**
- 账本增删改查齐全：列表 / 新建（预置 13 条分类）/ 改名 / 切换当前 / 删除（进回收站）
- 删除 = 进回收站，**15 天内可恢复**；到期连同该账本的分类与流水彻底删除（§5.2）
- **至少保留一本**：删到最后一本返回 400，否则分类/流水/统计接口会全部「没有当前账本」
- 注册即建第一本账本并预置 **13 条班级分类**（8 支出 + 5 收入，见 §3）
- 换届交接仍可用 **Excel 导出**（§5.5.1）；旧账本留着看或删进回收站都行

### 2.2 注册建账本
- 注册时只填账号信息 + 可选账本名；后端固定建 `class` 账本并预置班级模板
- 账本名不填则默认「班级账本」；前端注册向导是两步（账号 → 给账本起名），第二步可改名
- `user.current_book_id` 指向当前账本，存后端

### 2.3 数据隔离
- 分类 / 流水 / 统计全部按「当前账本」过滤
- 前端无需传 book_id，后端从当前用户取 current_book_id
- 防越权：所有查询强制带 book 归属校验（详见 §8 第 5 条）

### 2.4 有意不做的部分（不是漏了，是砍了）
| 砍掉 | 原因 |
|---|---|
| 个人 / 商家账本与模板 | 场景只有一个：班费。多类型会带来无用的统计分支 |
| 预算 | 班费没有「每月预算」概念 |
| AI 分析（规则引擎） | 名不副实、面试容易被问穿，且对班费无用 |
| 头像上传（OSS） | 单用户自用，无意义；也避免引入 AK/SK 与外部依赖 |
| 多用户共享账本 | 需要 `book_member` + 角色 + 全接口改权限校验（3–5 天），班费场景收益低 |
| 短信找回密码 | 需要短信通道（实名 + 按条计费）；邮箱一次性 token 已够用（§5.1） |

> **多账本管理原先在这张表里，后来做回来了**（需求变更）：现在支持列表 / 新建 / 改名 / 切换 /
> 删除（回收站 15 天），见 §2.1 与 §5.2。仍然不做的是：账本类型、多人共享账本、账本排序自定义。

## 3. 数据库设计

建库：`CREATE DATABASE bookkeep DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`

排序规则分两层：库/表/其余列用 `utf8mb4_0900_ai_ci`（MySQL 8 默认），`user.username` / `user.username_active` 显式用 `utf8mb4_0900_as_cs`（用户名区分大小写）。两列不同排序规则直接比较会报 `Illegal mix of collations`，列与字符串参数比较不受影响。

```sql
CREATE TABLE `user` (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  username       VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL, -- 显示名，可改
  password       VARCHAR(100) NOT NULL,          -- BCrypt
  email          VARCHAR(100) NOT NULL,          -- 登录凭据：必填，未删除行内唯一
  avatar         VARCHAR(500),                   -- 头像 URL（预留列，当前不做上传，见 §2.4）
  current_book_id BIGINT,                        -- 当前打开的记账本
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted        TINYINT DEFAULT 0,
  -- 唯一性只约束未删除行：deleted=1 时为 NULL，MySQL 多个 NULL 不冲突
  -- → 注销后用户名/邮箱都可被重新注册；活着的账号之间仍然互斥
  username_active VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
                  GENERATED ALWAYS AS (IF(`deleted` = 0, `username`, NULL)) STORED,
  email_active    VARCHAR(100)
                  GENERATED ALWAYS AS (IF(`deleted` = 0, `email`, NULL)) STORED,
  UNIQUE KEY uk_user_username_active (username_active),
  UNIQUE KEY uk_user_email_active (email_active),
  KEY idx_user_username (username)               -- 按用户名查（唯一索引已不覆盖 username）
);

CREATE TABLE book (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  name        VARCHAR(50) NOT NULL,
  type        VARCHAR(10) NOT NULL DEFAULT 'class',   -- 固定 'class'（班级账本）；列保留以便将来扩展
  share_token VARCHAR(32),                            -- 公示页随机 token（免登录只读，§5.9）
  share_token_expires_at DATETIME NULL,               -- 公示链接到期时间；NULL/过期 = 链接失效（分享时 +3 天）
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT DEFAULT 0,
  UNIQUE KEY uk_book_share_token (share_token)
);

CREATE TABLE category (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  book_id     BIGINT NOT NULL,
  name        VARCHAR(50) NOT NULL,
  type        VARCHAR(10) NOT NULL,              -- 'income' / 'expense'
  icon        VARCHAR(100),
  color       VARCHAR(20),
  sort_order  INT DEFAULT 0,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT DEFAULT 0
);

CREATE TABLE transaction (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  book_id     BIGINT NOT NULL,
  category_id BIGINT NOT NULL,
  type        VARCHAR(10) NOT NULL,              -- 'income' / 'expense'
  amount      DECIMAL(12,2) NOT NULL,            -- 金额用 DECIMAL，绝不用 double
  note        VARCHAR(255),
  trans_date  DATE NOT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT DEFAULT 0
);

CREATE TABLE budget (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  book_id     BIGINT NOT NULL,
  category_id BIGINT NOT NULL,
  month       VARCHAR(7) NOT NULL,               -- '2026-08'
  amount      DECIMAL(12,2) NOT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_book_cat_month (book_id, category_id, month)
);
```

### 分类模板（注册时按账本类型预置，当前只有 class）

| type | 支出分类（8） | 收入分类（5） |
|---|---|---|
| class | 班会物资、活动经费、聚餐、打印复印、场地、交通、奖品、其他 | 收取班费、学院拨款、活动赞助、结余转入、其他 |

- 图标/颜色存**语义 key**（如 `supplies` / `orange`），前端负责映射成图标组件与主题色，换图标库不用改数据
- 「结余转入」是给换届用的：新生活委员建账后第一笔记「上学期结余 X 元」，历史衔接清晰
- 模板实现在 `CategoryTemplate.classCategories()`；老库升级执行 `sql/migration_book_type_class.sql`

## 4. 工程与包结构（Maven 多模块）

依赖方向：`web → service → model / common`（单向，不反向依赖；`model` 与 `common` 互不依赖）
> 模块划分参考 VirtueEdu 的 `model` / `utils` / `web` 三段式，但**不照搬父 pom 的 `<dependencies>` 全局注入** —— 父 pom 只用 `dependencyManagement`，谁用谁声明。

```
D:\coding\bookkeep-server\                父工程（packaging=pom，只管模块聚合与依赖管理）
├── pom.xml                               spring-boot-starter-parent + 统一依赖版本管理
├── bookkeep-common\                      公共模块：统一返回 + 工具，不依赖业务，也不依赖 model
│   └── com.bookkeep.common
│       ├── Result、ResultCode、PageResult
│       ├── BusinessException
│       └── JwtUtil、SecurityUtil
├── bookkeep-model\                       数据模型模块：实体 + 请求/响应 DTO（只有数据形状，无业务逻辑）
│   └── com.bookkeep.model
│       ├── entity       User、Book、Category、Transaction（Budget 表保留但不建实体，见 §2.4）
│       └── dto
│           ├── req      RegisterReq、LoginReq、CategoryReq、TransactionReq、TransactionQuery、UserUpdateReq
│           └── resp     LoginResp、UserResp、BookResp、CategoryResp、TransactionResp、
│                        SummaryResp、CategoryStatResp、PublicReportResp
├── bookkeep-service\                     业务模块：数据访问 + 业务逻辑（mapper / service + impl）
│   └── com.bookkeep.service
│       ├── mapper       UserMapper、BookMapper、CategoryMapper、TransactionMapper（统计用 @Select 手写 SQL）
│       ├── 接口与 impl   AuthService、CategoryService、TransactionService、StatisticService、PublicReportService
│       ├── constant     CategoryTemplate（班级模板唯一来源）
│       └── export       TransactionExportService（导出：流水明细 + 汇总；实现见 §5.5.1）
└── bookkeep-web\                         启动模块：启动类 + 控制器 + 配置
    └── com.bookkeep.web
        ├── BookkeepApplication            主启动类（@SpringBootApplication + @MapperScan）
        ├── controller    AuthController、CategoryController、TransactionController、
        │                 StatisticController、PublicController（免登录只读）
        ├── config        SecurityConfig、MybatisPlusConfig、JacksonConfig
        ├── filter        JwtAuthenticationFilter（非 @Component，SecurityConfig 手动 new）
        ├── handler       GlobalExceptionHandler、JwtAuthenticationEntryPoint、JwtAccessDeniedHandler
        └── resources     application.yml、logback-spring.xml（application-local.yml 本地配置，gitignore）
```

> 约定：`bookkeep-web` 里还放 `aspect/LogAspect`（控制器调用与异常日志）与 `config/StartupInfoRunner`（启动自检）。

> 测试按标准 Maven 约定放**每个模块自己的 `src/test/java`**（不设独立 test 模块）：
> - `common`：JwtUtil / Result / ResultCode 纯单元测试
> - `service`：service 层 Mockito 单测、mapper 测试
> - `web`：`@SpringBootTest` + MockMvc 接口集成测试（完整上下文在此模块，DB 需可用）

### 各模块依赖清单

| 模块 | 依赖 | 说明 |
|---|---|---|
| `bookkeep-common` | lombok、jjwt、spring-security-core | 轻依赖，JwtUtil/SecurityUtil 用 |
| `bookkeep-model` | lombok、mybatis-plus-annotation、jakarta.validation-api | 实体 + DTO；只引注解包（会传递一个 mybatis 核心包），不拖整个 MyBatis-Plus 栈 |
| `bookkeep-service` | spring-boot-starter、mybatis-plus-spring-boot3-starter、mysql-connector-j、spring-boot-starter-validation、bookkeep-model、bookkeep-common | mapper/service+impl/constant/export |
| `bookkeep-web` | spring-boot-starter-web、spring-boot-starter-security、spring-boot-starter-aop、bookkeep-service、bookkeep-model | 启动/控制器/配置（controller 直接收发 DTO，故显式依赖 model） |
| 各模块 | spring-boot-starter-test(test) | 每模块自带单测；web 额外 spring-security-test |

> 导出实现：**先用 CSV（UTF-8 BOM，0 新依赖）**，Excel 能直接打开且中文不乱码，满足换届交接；
> 若 W2 还有时间再做「双 sheet 的真 Excel」，那时才引 `easyexcel`（或 `poi-ooxml`）。不要为了双 sheet 卡住主线。

### 日志功能
- `logback-spring.xml`（web 模块 resources）：控制台 + 滚动文件（`logs/bookkeep-server.log` 按天滚动，保留 30 天），ERROR 单独落 `-error.log`；日志带 `[环境]` 标识
- 环境级别：默认/非 prod → `com.bookkeep` DEBUG、mapper DEBUG（看 SQL）；`prod` 环境 → 业务 INFO、mapper INFO。启动时 `--spring.profiles.active=prod` 切换
- MyBatis-Plus SQL 日志改走 SLF4J（`log-impl: Slf4jImpl`），mapper 包设为 DEBUG 级别
- `LogAspect`（web 模块 aspect 包）：AOP 自动记录所有 controller 调用——方法/参数/耗时，超 1s 标 WARN，异常标 ERROR；自动脱敏 password/token 字段
- `StartupInfoRunner`（web 模块 config 包）：启动后打印应用名称/版本/端口/环境横幅（版本来自 spring-boot `build-info` 插件）
- 业务代码里直接用 Lombok `@Slf4j` 打日志

### 多模块注意点
- 四个子模块包名统一在 `com.bookkeep` 下，但启动类在 `com.bookkeep.web`，默认组件扫描只覆盖 `com.bookkeep.web`，**必须**给 `BookkeepApplication` 加 `scanBasePackages = "com.bookkeep"`，否则 model/service 的 Bean 扫不到
- `@MapperScan("com.bookkeep.service.mapper")` 加在主启动类或 MybatisPlusConfig
- `application.yml` 只在 web 模块，服务启动入口是 `bookkeep-web`
- 测试按标准约定放各模块 `src/test`，`@SpringBootTest` 集成测试放 web 模块
- 父工程 `dependencyManagement` 统一版本，子模块不写版本号

## 5. 接口契约

- 统一前缀 `/api`，认证用 `Authorization: Bearer <token>`
- 统一返回 `{ code, message, data }`，`code=200` 成功
- 常用 code：200 成功 / 400 参数错误 / 401 未登录或 token 失效 / 403 无权 / 500 服务器异常
- 出错时 **HTTP 状态码与 `code` 一致**（`ResponseEntity.status(code)`），成功为 200
  - **第 7 天定稿的实际策略（覆盖上一条，实现以此为准）**：
    - **业务错误 → 恒为 HTTP 200 + body.code**。`BusinessException` 不管 code 是 400/401/403 都一样，
      调用点不用关心状态码；例如 `/api/auth/me` 遇到已注销用户返回 200 + `code=401`
    - **认证 / 授权失败 → 真实 401 / 403 + 同一套 JSON 信封**：
      过滤器链里由 `JwtAuthenticationEntryPoint`（401，未登录）和 `JwtAccessDeniedHandler`（403，已登录但无权限）输出；
      方法级鉴权（`@PreAuthorize`）抛出的 `AccessDeniedException` 由 `GlobalExceptionHandler` 委托**同一个** 403 处理器，
      两条路径的 JSON 完全一致
    - 前端因此必须写两种分支：`then` 里判断 `code`（含 200 + `code=401` 的已注销用户，要清 token 跳登录），
      `catch` 里判断 `error.response.status === 401`（同理清 token 跳登录）、`=== 403` 提示无权限

### 5.0 前后端契约硬约定（写第一个页面前定死，改一次全前端返工）

前端由 AI 生成，下面 5 条必须固定，并把本节**直接喂给 AI**，而不是让它猜字段：

| # | 约定 | 具体值 |
|---|---|---|
| 1 | 信封与分页 | 信封 `{code, message, data}`；分页 `data` 固定 `{total, list}`（`PageResult`），入参固定 `page`（从 1 开始）/ `size`，**不用** `current` / `records` / `rows` |
| 2 | 金额类型 | **一律字符串**：`BigDecimal` 全局按字符串序列化（`JacksonConfig` 注册 `BigDecimal → String` 序列化器）。JS 的 `number` 是双精度，`0.1 + 0.2 !== 0.3`，金额累加/比较会算错 |
| 3 | 日期格式 | 日期 `yyyy-MM-dd`（`transDate` / `startDate` / `endDate`），月份 `yyyy-MM`（`month`）；不传时间戳、不让前端解析 |
| 4 | 错误处理 | 前端只写一个拦截器：`code !== 200` → 提示 `message`；`code === 401` → 清 token + 跳登录；`code === 403` → 提示无权限。业务错误 HTTP 仍是 200，**前端判断 `code`，不判断状态码** |
| 5 | 列表自带名称 | 列表/详情直接返回展示所需名称，禁止让前端二次查询：流水含 `categoryName` / `categoryIcon` / `categoryColor`（§5.5）。这条最容易被纯后端自测漏掉——`curl` 返回 `categoryId: 3` 看起来完全正常 |

### 5.1 认证

| 方法 | 路径 | 说明 | 请求体 / 参数 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册并创建班级账本（预置 13 条分类） | `{username, password, email, bookName?}` → 用户信息。**邮箱是登录凭据**：必填且未删除行内唯一；`username` 只是显示名；**没有 bookType / nickname**；`bookName` 为空默认「班级账本」 |
| POST | `/api/auth/login` | 登录 | `{email, password}` → `{token, user:{id, username, email, avatar, currentBookId}, books:[{id, name, type, shareToken, shareTokenExpiresAt, current}]}` |
| GET | `/api/auth/me` | 当前用户信息（含 currentBookId） | - |
| POST | `/api/auth/forgot-password` | 申请重置密码（匿名） | `{email}`；该邮箱已注册才发信，**永远**返回同一句「如果该邮箱已注册，我们已经发送重置邮件」 |
| POST | `/api/auth/reset-password` | 用邮件里的一次性 token 重置（匿名） | `{token, newPassword}`；成功后 `token_version + 1`，旧会话全部失效 |

> `books` 是平铺在外层（不是 `user.books`）。虽然当前只有一个账本，字段保留是为了兼容已有前端契约。
> `shareToken` / `shareTokenExpiresAt` 是当前公示链接的状态：**从未分享过为 `null`**，链接 3 天有效；
> 生成/更换链接走 `POST /api/books/{id}/share`（§5.2），前端不要自己拼 `book.id`。
> **登录凭据是邮箱**（未删除行内唯一，`uk_user_email_active`）：用户名只是显示名，可在账号设置里随便改，
> 所以登录响应里没有 `nickname` 字段。邮箱比较不区分大小写（`utf8mb4_0900_ai_ci`），
> 「邮箱或密码错误」是同一句提示，不让接口变成邮箱是否注册过的探测器。
> **邮箱自本版起必填且唯一（未删除行内）**：它是登录凭据，也是找回密码的通道。
> 目录规则：`email` 用表默认排序规则 `utf8mb4_0900_ai_ci`（大小写不敏感，符合邮箱语义）；
> 唯一性由生成列 `email_active` 负责，注销后为 NULL → 邮箱随账号一起释放。

### 5.2 账本

多账本 + 回收站（§2.1）。分类 / 流水 / 统计 / 导出**只认当前账本**，切换账本后前端要重新拉这些数据。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/books` | 我的账本（不含回收站），带 `current` 标记 |
| POST | `/api/books` | 新建 `{name}`，事务内预置 13 条分类，并自动切为当前 |
| PUT | `/api/books/{id}` | 重命名 `{name}`，已有 `shareToken` 不变 |
| POST | `/api/books/{id}/share` | 生成 / 重生公示链接 → `{token, expiresAt}`；**每次调用都换新 token** |
| PUT | `/api/books/{id}/current` | 切换当前账本，返回切换后的完整列表 |
| DELETE | `/api/books/{id}` | 删除 → 进回收站；**至少保留一本** |
| GET | `/api/books/trash` | 回收站，带 `deletedAt` / `expireAt` |
| DELETE | `/api/books/{id}/permanent` | 立即彻底删除回收站里的一本（连同分类/流水，不可恢复） |
| PUT | `/api/books/{id}/restore` | 从回收站恢复（不自动切为当前） |

规则：

- **上限 10 本**，超出 400
- 归属一律按 `book.user_id` 校验，别人的账本 id 一律 404（不暴露 id 是否有效）
- 公示链接：注册/新建账本**不发** token，必须 `POST /api/books/{id}/share` 显式生成；
  `expiresAt = now + 3 天`，再分享一次就换新 token、旧链接立即失效（单链接模型，避免一叠永不过期的凭证）
- 删除写 `deleted=1 + deleted_at`（不能用 MP 的 `deleteById`：它不写 `deleted_at`，回收站就算不出到期日）
- 删的是当前账本时，`current_book_id` 自动重指到剩下最早的一本
- 到期清理：`purgeExpired()` 每天 3:30 由 `BookPurgeJob` 执行，**同时**在 `GET /api/books` 里顺手做一遍
  （本机不保证 7×24 开机，停机期间错过的清理要有人补）；清理顺序是流水 → 分类 → 账本
- 回收站列表与恢复必须手写 SQL：MP 的逻辑删除会给生成 SQL 自动拼 `deleted=0`，用 Wrapper 永远够不到已删行

### 5.3 用户

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT | `/api/users/me` | 改用户名 / 登录邮箱 `{username, email, password?}`：**改邮箱必须带当前密码**；用户名是显示名，改它不需要密码（✅ 已实现） |
| PUT | `/api/users/me/password` | 改密码 `{oldPassword, newPassword}`；成功后 `token_version + 1`，所有旧 token 立即失效（✅ 已实现） |
| DELETE | `/api/users/me` | 注销账户 `{password}` 二次确认：**立即物理删除**本人 + 名下账本/分类/流水/token，JWT 立即失效，用户名与邮箱释放（✅ 已实现） |

- 改密码**必须校验旧密码**（否则拿到手机的人可以直接改掉），规则与注册一致（6–32 位）
- 注销账户的定稿口径：
  - 带当前密码确认；**不做软删除、不进回收站**：直接删 user / book / category / transaction / password_reset_token
  - 因此用户名与邮箱立即释放，可重新注册；数据**不可恢复**（要留档请在注销前用导出 CSV 或备份库）
  - 删除顺序必须是子表优先：流水 → 分类 → 账本 → 用户（子查询依赖 book 还在，别把顺序写反）
  - 旧 token 因用户行已不存在，在业务层返回 401（HTTP 200，沿用既有策略）
- 前端入口：顶栏用户名 → 下拉「账号设置」（`/settings`），含改用户名/邮箱（改邮箱要当前密码）、改密码、注销账户（危险操作单独二次确认）
- 头像上传**不做**（避免引入 OSS 与外部依赖）。

### 5.4 分类（按当前账本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/categories?type=` | 分类列表，可按 income/expense 过滤 |
| POST | `/api/categories` | 新增 `{name, type, icon?, color?}` |
| PUT | `/api/categories/{id}` | 修改（仅自己账本） |
| DELETE | `/api/categories/{id}` | 删除（仅自己账本；若已有流水则业务异常提示） |

### 5.5 流水（按当前账本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/transactions?page=1&size=10&type=&categoryId=&startDate=&endDate=&keyword=` | 分页查询 → PageResult |
| POST | `/api/transactions` | 新增 `{categoryId, type, amount, transDate, note?}`；**支出不能超过当前余额** |
| PUT | `/api/transactions/{id}` | 修改（仅自己账本，同样校验余额） |
| DELETE | `/api/transactions/{id}` | 删除（仅自己账本） |
| GET | `/api/transactions/export?startDate=&endDate=` | 导出 Excel/CSV（**含逐行结余**），换届与公示用 |

返回 DTO 含 `categoryName`、`categoryIcon`、`categoryColor`。

#### 5.5.1 班费收支表设计（第 7 天定稿）

**形态**：真实班费表是「流水账 + 逐行结余」，不是分类汇总表：

| 日期 | 分类 | 摘要 | 收入 | 支出 | 结余 |
|---|---|---|---|---|---|
| 2026-09-05 | 收取班费 | 全班 38 人 × 50 | 1900.00 | | 1900.00 |
| 2026-09-20 | 班会物资 | 横幅+气球 | | 86.50 | 1813.50 |

**逐行结余怎么算**：MySQL 8 窗口函数，在分页**之前**算完（前端累加一上分页就错）：

```sql
WITH running AS (
  SELECT t.id, t.trans_date, c.name AS category_name, t.type, t.amount, t.note,
         SUM(CASE WHEN t.type = 'income' THEN t.amount ELSE -t.amount END)
             OVER (ORDER BY t.trans_date, t.id) AS balance_after
  FROM `transaction` t
  JOIN category c ON c.id = t.category_id
  WHERE t.book_id = #{bookId} AND t.deleted = 0
)
SELECT * FROM running ORDER BY trans_date DESC, id DESC LIMIT #{size} OFFSET #{offset}
```

- `ORDER BY trans_date, id` 里的 **id 不能省**，否则同一天多笔顺序不稳定，余额会跳
- **窗口函数必须在外层过滤之前算**：`WITH running` 里只按 `book_id` 过滤，月份/日期条件写在外层 `WHERE`。
  先按月过滤再累计，每月第一行都会从 0 开始，数字全错（W2 的 `selectMonthWithRunningBalance` 就是这么写的）
- **取舍**：普通明细列表**不返回**结余列（只返回金额）；**导出与公示页才带** `balanceAfter`（它们是全量查询，语义清晰）
- 余额校验：`当前余额 = Σ收入 − Σ支出`，新增/修改**支出**时超过余额 → `400「班费余额不足，当前余额 X」`
- 统计口径：本月收入/支出/结余；累计结余 = 全账本 `sumBalance`（不等于本月结余）；分类占比按支出 `GROUP BY category_id`

**导出格式（W2 落地：单个 CSV，两个区块）**：

- 抬头区块：账本名 / 导出时间 / 统计区间 / 期间收入 / 期间支出 / **期初结余** / **期末结余**
- `流水明细` 区块：日期 / 分类 / 摘要 / 收入 / 支出 / 结余，最后一行「合计」
- `分类合计` 区块：分类 / 类型 / 金额（支出在前，同类内金额降序）
- 期初结余 = 截至 `startDate − 1 天` 的累计余额，期末结余 = 截至 `endDate` 的累计余额（区间为空时仍自洽）
- 硬要求：UTF-8 BOM（否则 Excel 中文乱码）、金额固定两位小数、日期 `yyyy-MM-dd` 文本、
  **备注里的逗号 / 引号 / 换行必须按 CSV 规则加引号并双写引号**，否则一行会被 Excel 挤错列
- 双 sheet 的真 Excel 属 C 档（引 `easyexcel` 或 `poi-ooxml`），先用 CSV 把主线走完，不阻塞
- 接口行为：成功返回 `text/csv` + `Content-Disposition: attachment`（中文文件名走 `filename*=UTF-8''`）；
  参数错误仍是 HTTP 200 + JSON 信封（`code=400`），前端要按「响应类型」分流：blob 才当文件

### 5.6 统计（按当前账本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/statistics/summary?month=2026-08` | `{month, income, expense, balance, totalBalance}`（balance = 该月收支差，totalBalance = 全账本累计） |
| GET | `/api/statistics/category?month=&type=expense` | 分类占比 `[{categoryId, categoryName, categoryIcon, categoryColor, amount, percent}]` |

- `month` 不传取当月（北京时间），格式必须是 `yyyy-MM`，格式错返回 400 而不是空数据（空数据会被误读成「这个月没记账」）
- `percent` 保留两位小数、四舍五入，**各项相加可能不等于 100.00**（例：三项各 33.33），前端别用它反算金额
- 分类项带上 `categoryId/icon/color` 是为了饼图图例直接出图标（§5.0 第 5 条），不是允许前端再查分类
- 统计口径只在 `StatisticCalculator` 实现一次：登录态统计与免登录公示页共用，避免两处数字不一致

月度趋势 / 每日趋势 / 利润 / 成本结构**不做**（图表的边际价值低于部署与导出；需要时再加）。

### 5.7 预算

**不做**（见 §2.4）。表结构保留在 `schema.sql` 里，不建实体、不写接口。

### 5.8 AI 分析

**不做**（见 §2.4）。原「规则引擎 + level 预警」的设计整体放弃。

### 5.9 只读公示页（免登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/public/report/{shareToken}?month=2026-08` | 免登录只读：本月收入/支出/结余 + 分类占比 + 明细（含逐行结余） |

响应体（W2 定稿）：`{bookName, month, income, expense, balance, totalBalance, categories:[…], transactions:[{transDate, type, amount, note, categoryName, categoryIcon, categoryColor, balanceAfter}]}`

- `book.share_token` + `book.share_token_expires_at`：32 位随机串（`UUID` 去横线）+ 到期时间，
  由 `POST /api/books/{id}/share` 生成，**有效期 3 天**；再分享一次即换新 token，旧链接立即失效
- 查询条件必须同时带 `share_token = ?` 和 `share_token_expires_at > now()`：过期、从未分享、账本已注销
  一律 `code=404「公示页不存在或已失效」`，**绝不能回落到「当前登录用户的账本」**
- 明细按日期**升序**返回：逐行结余只有从上往下读才成立，翻页/倒序展示由前端处理
- 明细不带 `id`、整页不含 `username`/`email`/`userId`：这个接口匿名可访问，能少暴露一个字段就少一个
- 只读、**不含任何个人信息**（只出账本名与金额），前端页面路径 `/report/{token}`
- 设计意图：同学看账不需要账号，因此**公网只需暴露这一个只读接口**，登录入口的风险面最小
- `SecurityConfig` 只放行 `/api/public/report/**`，其余路径（含统计）仍要 token——`PublicReportApiTest` / `ShareLinkApiTest` 钉住了这一点
- 为什么是「单链接 + 3 天」而不是「多条并存」：班费公示是短期行为（发一次、看几天），
  单链接让「我能随时让旧链接失效」成立；要同时发给多个群再评估多链接表

## 6. 开发顺序（第 7 天重排：一个月 / 最多五周做完，本地为主 + 云取证）

**范围（定稿）**：P1 已完成；剩下 P2 分类 + 流水、P3 统计（砍到 2 个接口）、导出、公示页、单机打包、云端取证。
**砍掉**：P4 多账本 + 预算、P5 AI 分析、P5.5 头像上传、多用户共享账本（见 §2.4）。

### 6.0 四档优先级（时间不够就从下往上砍）

| 档 | 内容 | 状态 |
|---|---|---|
| **A 档（必须，否则项目失去意义）** | P2 分类 + 流水后端（隔离/分页筛选/余额校验）+ 记一笔 + 明细列表 + CSV/Excel 导出 + 单机跑起来 + 每日备份 | 第 1–3 周 |
| **B 档（争取）** | 本月收支/结余 + 分类占比、只读公示页 | 第 2–4 周 |
| **C 档（有余力）** | Docker Compose 云端取证、HTTPS、登录限流、demo 视频、简历精修 | 第 4–5 周 |
| **不做** | 多账本、预算、AI 分析、头像、多用户共享、手机离线、邮件找回密码 | — |

### 6.1 五周排期（每周都有可验收产出）

| 周 | 后端 | 前端（AI 写，本人验收） | 周末验收 |
|---|---|---|---|
| **W1** | P2 分类 + 流水（book 隔离、分页筛选、余额校验）+ 单测 + `http/*.http` | 记一笔 + 明细列表（移动优先卡片） | `localhost` 能登录、能记一笔、列表能看到；**当天开始记真实班费** |
| **W2** | `class` 收敛已提前完成；导出（CSV、UTF-8 BOM、逐行结余）+ 统计 summary/category + 公示页接口 ✅ 代码完成 | 统计卡片 + 导出按钮 + 公示页 | 导出的 CSV 发班群不丢人；公示链接免登录能看 |
| **W3** | 单机打包：`repackage` fat jar + `deploy/nginx/nginx.conf` + 启停/备份脚本 ✅ | 生产构建（`dist` 交给 Nginx，history 路由 + `try_files`）✅ | 双击 `start-all.bat` 能用；重启电脑数据还在；备份能恢复；手机同 WiFi 能开 |
| **W4** | Dockerfile + compose（本机跑通）→ 试用期上云：HTTPS、密钥外部化、登录限流（含 `X-Real-IP`）、导入真实数据、**备份恢复演练** | 无（前端产物复用） | 手机 4G 打开 `https://` 地址能记一笔；七项取证材料齐全 |
| **W5** | 缓冲修 bug + 真实数据复盘 | 无 | README / `deploy/README.md` 能让别人 30 分钟复现；简历两行定稿；2 分钟 demo 录屏 |

**三条硬约束**：
1. **W1 必须跑通「记一笔」**——这是唯一不能延的部分，否则没有真实数据，项目失去说服力
2. **W4 才申请服务器试用**，W1–W3 全部本地，别浪费试用期
3. **W4 上线前必须先完成登录限流与密钥外部化**（公网暴露的前置条件，见 §9 第 1 条）

### 6.2 前后端配合节奏（按模块交替，禁止「全后端做完再统一联调」）

原则：**后端推完一个模块（接口 + 测试 + `.http` 可重放请求）→ 立刻让 AI 写对应页面 → 联调验收 → 再进下一个模块。**

不做「全后端先行」的原因：

- 列表接口几乎必然要 join 出名称（见 §5.0 第 5 条），纯后端自测时看不出问题，等前端一联调才发现缺字段
- 联调问题会一次堆到十个（字段名、日期格式、金额精度、401 跳转、proxy），无法按顺序排查
- 到第 3 周末才第一次看到能点的页面，中期没有任何「可演示」的成果

配套要求：

- 前端脚手架**第一周就建起来**（Vite + 代理 `/api` → `http://localhost:8080`）——已验证：`vite.config.ts` 代理、`utils/token.ts` + `stores/auth.ts` token 持久化、`api/request.ts` 的 `code`/`status` 双分支 401 处理；验收步骤见 `bookkeep-web/README.md`
- 每个模块的后端完成后，用可重放的 HTTP 脚本跑通并**留在仓库里**（`http/`）：IDEA Ultimate 的 HTTP Client / VS Code 的 REST Client / Apifox 任选，语法通用；社区版 IDEA 没有 HTTP Client
- 不要让 AI「一次性生成 10 个页面」：先打通一条完整链路，再复制模式扩页面
- 前端产出按**可观察行为**验收（连点只发一次请求、刷新后数据还在、空数据显示 0），而不是「页面能打开」

### P1 细化（全部已完成）

- **第 0 天 ✅**：建库建表 + `application.yml`（UTF-8 + 环境变量 `${DB_USER}/${DB_PWD}`）+ `ResultCode`/`Result`/`PageResult`/`BusinessException`/`JwtUtil` + `BookkeepApplication(scanBasePackages)` + `DataSourceConnectionTest`
- **第 1 天 ✅**：`User` 实体 + `UserMapper` + `UserMapperTest`（持久层 + 逻辑删除验证）
- **第 2 天 ✅**：`Book`/`Category` 实体 + `BookMapper`/`CategoryMapper` + `CategoryTemplate` + `BookMapperTest`/`CategoryMapperTest`
- **第 3 天 ✅**：最小 `SecurityConfig`（先 `permitAll`）+ `GlobalExceptionHandler` + `GlobalExceptionHandlerTest`
- **第 4 天 ✅**：注册接口（事务内建用户 → 建账本 → `Db.saveBatch` 写模板分类 → 回写 `current_book_id`）；同日把实体与 DTO 拆到 `bookkeep-model`
- **第 5 天 ✅**：登录发 token（BCrypt 校验、防用户名枚举、`{token, user, books}` 平铺结构）
- **第 6 天 ✅**：`SecurityUtil` + `JwtAuthenticationFilter`（只认证不写响应）+ `JwtAuthenticationEntryPoint`（真实 401）+ 收紧 `SecurityConfig` + `GET /api/auth/me` + `AuthMeTest`
- **第 7 天 ✅**：`JwtAccessDeniedHandler`（真实 403）+ `GlobalExceptionHandler` 接管 `AccessDeniedException` + 「HTTP 状态码 vs body.code」定稿（§5）+ `SecurityJsonHandlerTest` + `http/auth.http`
- **第 7 天（范围收敛）✅**：砍掉 personal / merchant，`book.type` 固定 `class`（`DEFAULT 'class'`）+ 13 条班级分类模板 + `book.share_token`（公示页）+ `RegisterReq` 去掉 `bookType`、`bookName` 改可选（默认「班级账本」）+ 相关测试重写 + `sql/migration_book_type_class.sql`

### W2 细化（P3 统计 + 导出 + 公示页，后端已完成）

- `StatisticCalculator`（`service/support`）：**统计口径唯一实现点**，登录态统计与免登录公示页共用；
  月份参数统一在这里解析（空 → 当月北京时间，格式错 → 400）
- `GET /api/statistics/summary` + `GET /api/statistics/category`（`StatisticController`）：本月收支/结余 + 累计余额；
  分类占比两位小数，默认 `type=expense`
- `GET /api/transactions/export`（`TransactionExportService`）：CSV（UTF-8 BOM）+ 抬头汇总 + 明细逐行结余 +
  分类合计；期初/期末结余按区间的边界日计算；成功返回文件流，参数错误仍走 JSON 信封
- `GET /api/public/report/{shareToken}`（`PublicController` + `PublicReportService`）：免登录只读，
  `SecurityConfig` 只放行这一条路径；账本从 token 反查，**且必须未过期**，查不到 404
- 契约补充：`POST /api/books/{id}/share` → `{token, expiresAt}`（3 天有效，每次分享重新生成），
  登录响应里的 `books[].shareToken` 只是「当前链接」的快照，可能为 null
- 测试：`StatisticApiTest`（6 例，含手算口径/越权/参数校验）、`ExportApiTest`（7 例，含 BOM、CSV 转义、
  区间期初、空账本、越权）、`PublicReportApiTest`（6 例，含免登录、跨月结余、token 边界、不泄露个人信息）、
  `ShareLinkApiTest`（5 例，含 3 天有效期、再分享旧链接 404、过期 404、越权 404、未登录 401）；
  另有不需要数据库的单元测试 `TransactionExportServiceTest`（5 例）与 `StatisticCalculatorTest`（3 例）
- `http/statistics.http`、`http/public.http` 可重放验收脚本

## 7. 自查清单

- **P1 ✅**：注册 → 登录 → 带 token 访问 `/api/auth/me` 成功；无 token 返回真实 401 JSON；错参 400；401/403 都是 JSON 信封而不是 HTML 错误页。脚本：`http/auth.http`
- **P1 收敛 ✅**：注册请求不含 `bookType`；账本 `type='class'`；预置 13 条班级分类（8 支出 + 5 收入）；`bookName` 不填默认「班级账本」
- **P2（W1 周末）✅**：能记一笔、列表能看到；按日期/类型/分类/关键字分页筛选正常；A 用户看不到 B 用户数据（逐接口验证）；支出超过余额返回 400
- **注册向导 + 账本管理 ✅**：`/register` 两步（账号 → 账本名）→ 自动登录进首页，首页标题是刚起的名字；
  新建第二本后用切换器切回来，分类和流水都跟着换（数据隔离）；删除进回收站、15 天内可恢复；
  只剩一本时删除返回 400；把 `deleted_at` 改到 16 天前再刷新列表，账本连同分类与流水被物理删除
  （`BookManageApiTest` 6 例钉住这些行为）
- **P3 + 导出（W2 后端，代码完成 / 待跑测试与手工验收）**：手算一个月数据，`summary`/`category` 与手算一致；
  导出的 CSV 用 Excel 打开中文不乱码、金额两位小数、逐行结余正确，合计行与 `summary` 对齐；
  公示链接免登录能看到本月收支。脚本：`http/statistics.http`、`http/public.http`
- **本地部署（W3 周末）✅**：`deploy/` 里有启停/备份/恢复脚本与 Nginx 配置模板；已实测双击 `start-all.bat` 起 Nginx(80) + 后端(`127.0.0.1:8080`)，
  `/`、`/login`、`/report/xxx` 刷新都回落到 `index.html`，`/api/**` 经反代前缀不丢、未登录返回真实 401 JSON；
  备份 → 删一行流水 → 恢复 → 行数复原；`netstat` 确认后端只监听 `127.0.0.1:8080`。
  **仍待你亲自验的两条**：重启电脑后数据还在（MySQL84 已 `Automatic`）；手机同 WiFi 打开（需先放行 80 入站，`deploy/README.md` 有 `netsh` 命令）
- **找回密码（邮箱一次性 token）**：注册时邮箱必填（也是登录凭据），忘记密码只填邮箱，
  邮件里给 15 分钟有效的一次性链接；重置成功即 `token_version + 1`，旧会话全部失效。
  **命令行的 `--reset-password` 已删除**——邮箱是唯一的重置通道，SMTP 必须配好（`deploy/env.ps1` 的 `MAIL_*`）
- **云端（W4 周末）**：手机 4G 打开 `https://` 地址能登录、能记一笔；`docker compose ps` 三个容器 healthy；限流生效（连错 6 次被拒）；取证材料七项齐全
- **安全/运维（第 4–5 周）**：邮箱重置后**新密码能登录、旧 token 立刻 401、链接用过即废**；`docker compose down && up` 后数据仍在（卷挂载正确）；备份恢复演练过一次
- **联调（每模块一次，§6.2）**：逐条核对 §5.0 五项——分页是 `{total, list}`、金额是字符串、日期是 `yyyy-MM-dd`、401 清 token 跳登录、列表直接带名称

## 8. 避坑清单

1. SpringBoot3 必须用 `mybatis-plus-spring-boot3-starter`（不是 `mybatis-plus-boot-starter`）
2. jjwt 0.12.x API 与旧版不同：`Jwts.builder().signWith(key)` 需传 `SecretKey` 对象（`Keys.hmacShaKeyFor(secretBytes)`），不是字符串
3. 金额用 `BigDecimal`，数据库 `DECIMAL(12,2)`，不用 double
4. JWT 过滤器注册在 `UsernamePasswordAuthenticationFilter` 之前；**过滤器只做认证、不写响应**，token 无效时不认证并继续走链，401 JSON 统一由 `JwtAuthenticationEntryPoint` 输出（在过滤器里再写一次会出现两种 401 格式）；「认证」里还包含一次 `user.token_version` 比对（见第 34 条）
5. `SecurityUtil.getUserId()` 从 SecurityContext 取当前用户，Controller 不从 token 现拆
6. 逻辑删除：MyBatis-Plus 全局配置默认拼 `deleted=0`，但**手写 `@Select` 的 SQL 不会自动拼**，统计 SQL 要自己加
7. 日期统一 `yyyy-MM-dd` 传参，统计按 `DATE_FORMAT(trans_date,'%Y-%m')=#{month}` 比较，避免时区坑
8. 跨域：开发用 Vite 代理（`/api` → `http://localhost:8080`）、生产用 nginx 反向代理，前后端同源，**无需 `CorsConfig`**；Jackson 日期格式按需配 `JacksonConfig`（`spring.jackson.date-format` 只对 `java.util.Date` 生效，LocalDate/LocalDateTime 需单独配）
9. 删除分类前先查有没有流水引用（有则 400 提示「该分类下已有 N 笔流水」），不要级联删流水——账目不能因为删一个分类就凭空少掉
10. 统计接口结果补零（本项目的 summary/category 按月过滤，暂不涉及补零；将来加趋势图时务必补零，否则前端图表会断层）
11–14. ~~OSS / 头像上传~~：头像功能已砍（§2.4），这四条暂不适用。若将来要加：JDK17 需额外引 `jaxb-api`/`jaxb-runtime`/`activation`；AK/SK 走环境变量；限制类型与大小；`LogAspect` 需跳过 `MultipartFile` 序列化
15. 注册接口**非幂等**：用户连点两次 → 第一笔成功、第二笔 400 `用户名已存在`。前端必须按钮单飞（`submitting` 标志 + `finally` 复位，**不能**只靠 debounce，debounce 只是把重复请求延后发出），且收到 `用户名已存在` 时引导「去登录」而不是报「注册失败」——那多半是自己上一次点击已经成功。后端不做「同名同密码视为成功」的伪幂等
16. 金额与日期的序列化必须在联调前定死：`BigDecimal` 按**字符串**返回（JS 双精度会让 `0.1+0.2` 出错）、`LocalDate` 统一 `yyyy-MM-dd`（`spring.jackson.date-format` 只对 `java.util.Date` 生效）；这两条漏了要等前端一联调才暴露（见 §5.0）
17. 契约漂移是前后端返工的第一大来源：分页字段名、金额类型、日期格式、错误码语义、列表是否带名称——五项定死后再写第一个页面，并把 §5.0 直接喂给 AI，别让它猜字段
18. 「先全后端、最后统一联调」是工期失控的典型路径：一个模块的后端 + 测试 + `.http` 跑通就立刻联调，见 §6.1
19. `@RestControllerAdvice` 的兜底 `@ExceptionHandler(Exception.class)` 会**吞掉** Spring Security 的
    `AccessDeniedException`（`@PreAuthorize` 拒绝时抛的就是它），把 403 变成 `200 + code=500`，
    而且**不会**再走到过滤器链的 `AccessDeniedHandler`。必须显式声明
    `@ExceptionHandler(AccessDeniedException.class)` 并委托同一个 403 处理器（见 §5 策略）
20. 前端 `src/types/api.ts` 是后端 DTO 的**镜像**：改 DTO（加字段、改类型、改 nullable）必须同步改它，
    否则 TS 会把旧字段当成正确契约一路编过，运行时才拿到 `undefined`。反过来也成立——页面报
    `Property 'xxx' does not exist` 时，先怀疑两边字段名不一致（大小写也算）
21. **Nginx 反代后 `request.getRemoteAddr()` 永远是 `127.0.0.1`**：按 IP 做的限流会变成「全站共用一个 IP」，
    谁都能把别人锁死。必须两头配：Nginx `proxy_set_header X-Real-IP $remote_addr` +
    后端优先读 `X-Real-IP`（回落 `getRemoteAddr()`）。同理日志里的真实客户端 IP 也依赖这个头
22. **SPA 放在 Nginx / 静态目录必须处理刷新 404**：前端用 `createWebHistory()` 时，直接访问 `/login`
    会变成找静态文件 → 404。Nginx 加 `try_files $uri $uri/ /index.html`；单体 jar 方案则改用 hash 路由
23. **MySQL 必须 8.0+**：schema 用了 `utf8mb4_0900_as_cs`（5.7 没有）和生成列唯一索引
    `uk_user_username_active`。安装/部署脚本要检查版本，否则建表直接失败
24. **找回密码只能走邮箱一次性 token，绝不能开「无密码改任意账号」的接口**：`forgot-password` 只收邮箱、
    无论是否存在都返回同一句提示（防枚举），`reset-password` 只认 15 分钟有效、
    用过即废的 token（库里存 sha256，不存明文）。SMTP 配在 `deploy/env.ps1`（`MAIL_USERNAME` /
    `MAIL_PASSWORD` / `MAIL_FROM`），不要写进仓库；改密码成功后一律 `token_version + 1`（见第 34 条）
25. **服务器试用期是稀缺资源**：功能没做完就先别申请，否则期限在写代码时被烧掉。
    顺序固定为「本地全做完 → 申请试用 → 一次完成上云 + 取证（截图/录屏/备份演练）」
26. **别在事务方法里用 MyBatis-Plus 的静态 `Db.saveBatch(...)`**：它自己取 `SqlSession`，
    外层 `@Transactional` + MySQL `REPEATABLE READ` 时，写入可能落在调用方事务快照之外。
    症状极具误导性：SQL 日志显示 13 条 insert 都执行了，同一事务里查却是 0 行
    （P2 的 13 个接口测试全挂在这里）。固定 13 条模板分类直接逐条 `mapper.insert()` 即可；
    真要批量导入，用 Spring 托管服务上的 `IService.saveBatch`，永远别用静态 `Db` 工具
27. **窗口函数要放在「外层筛选」之前**：`WITH running AS (…全账本…) SELECT … WHERE DATE_FORMAT(...) = #{month}`
    才对。写成 `WITH running AS (… WHERE 月份…)` 时每月第一行都从 0 开始累计，公示页/导出的结余全错，
    而且只有跨月数据才暴露（单月测试永远绿）
28. **CSV 不是「拼逗号」**：备注里出现 `,` `"` 或换行时必须整体加引号、内部引号双写；
    同时文件头要写 UTF-8 BOM，否则 Excel 打开是乱码。导出的行是「发到班群」的凭证，
    这两条出错比接口 500 更难被发现
29. **下载接口不能用统一 JSON 信封**：成功时返回 `text/csv` + `Content-Disposition`，
    失败（如日期区间颠倒）仍走 `200 + {code:400}`。前端必须按响应类型分流：
    `responseType: 'blob'` 时才当文件保存，否则会把 JSON 当 CSV 存下来
30. **回收站不能用 MyBatis-Plus 的 Wrapper 读或恢复**：逻辑删除插件会给生成 SQL 自动拼 `deleted=0`，
    `selectOne(eq("deleted", 1))` 会被改成 `deleted=1 AND deleted=0` 永远为空，
    `update(set("deleted", 0))` 同样改不到——查回收站、恢复、物理删除都必须手写 `@Select/@Update/@Delete`
31. **逻辑删除不会自动写时间列**：想知道「删了多久」必须另加 `deleted_at`（`updated_at` 是 ON UPDATE 自动列，
    改名也会动它）。`deleteById` 只写 `deleted=1`，回收站到期时间得自己 `set("deleted_at", now)`
32. **Windows 批处理装不下 UTF-8 中文**：`.bat` 里写中文，配上 `chcp 65001` 后 cmd 仍会按字节错位解析
    （典型症状是 `'ho' 不是内部或外部命令`、标签被吃掉一半）。带 LF 行尾时更糟。
    所以 `deploy/*.bat` 只放 ASCII 启动器，逻辑和中文提示都在同名 `.ps1` 里（UTF-8 **带 BOM** + CRLF）；
    改这几个文件时别把中文搬回 `.bat`
33. **`mysql -e "source D:/..."` 在 Windows 会炸**：盘符的冒号让客户端把路径当标识符（`ERROR 1059`）。
    恢复脚本改成 `Start-Process -RedirectStandardInput <dump 文件>`，把 SQL 直接喂给 mysql，绕开 `source`
34. **改密码必须让旧 JWT 失效，否则「重置密码」只是换了个密码**：JWT 是无状态的，签出去就管到过期。
    做法是 `user.token_version`（默认 0）+ token 里的 `ver` claim：改密码/重置时 `+1`，过滤器每个请求
    `selectById` 比对一次，不一致就按未认证处理。别为了「过滤器零查库」把这段挪到业务层——业务层覆盖不全
    （账本列表、统计、导出都只拿 userId），漏一个接口就等于没做。代价是每个已认证请求多一次主键查询，本项目量级可忽略。
    迁移 `sql/migration_user_token_version.sql` 必须执行，否则所有用户查询报 `Unknown column 'token_version'`
35. **公示链接的 3 天有效期两端必须用同一个时钟**：写入 `share_token_expires_at` 与查询比较都用
    `LocalDateTime.now()`（应用时钟）。别一边写 `NOW()` 一边用 Java 时间——容器/DB 时钟差几秒，
    就会出现「刚生成的链接偶发 404」或「过期链接多活一会儿」。迁移里补老数据用 `DATE_ADD(NOW(), …)`
    只是一次性补值，不参与运行时判断

## 9. 已知缺口（P1 有意不做，上线前必须回看）

1. **无限流**（**公网部署的前置条件**）：注册 / 登录都没有失败计数、验证码或 IP 限流，可被脚本批量注册和撞库；
   BCrypt 每次校验约 60–100 ms，几十个并发登录就能打满 1 核小机器的 CPU。
   登录侧「用户不存在与密码错误同一提示」只防枚举，不防暴力尝试。
   计划（W4 上线前必须完成）：内存版 Caffeine，**按 `IP + email` 计失败次数，5 次 / 5 分钟**，
   成功登录后清零；再补一条**单 IP 每分钟 30 次**的粗粒度限制（防用户名喷洒）。
   Nginx 必须透传 `X-Real-IP`（见 §8 第 21 条），否则按 IP 限流形同虚设。
   **本地 / 局域网使用不需要限流**（外网访问不到）。
2. **邮箱必填且唯一，但未验证**：`user.email` 自本版起是登录凭据，必填、未删除行内唯一
   （`email_active` 生成列 + 唯一索引），注销后随账号释放。邮箱**不做验证**（不发确认邮件），
   前端不得展示「已验证」；同一邮箱不能挂多个账号（注册会 400）。
   这样 `forgot-password` 只凭邮箱就能定位账号，不用再让用户记用户名。
3. **找回密码已由邮箱一次性 token 承担**（`forgot-password` + `reset-password`，见 §10.2）：
   - token 只存 sha256、15 分钟有效、用过即废；重置成功 `token_version + 1`，旧会话全部失效
   - `forgot-password` 永远返回同一句提示，内部对邮箱与 IP 做进程内限流
   - **`--reset-password` 命令行入口已删除**：邮箱是唯一的重置通道，SMTP 必须配置好；
     缺点写在这里——SMTP 未配或密码丢失时，只能由维护者手工 `UPDATE user SET password=<BCrypt 哈希>`
   - 想彻底关掉「万一 SMTP 挂了」的死角：`deploy/README.md` 里保留手工改库的步骤，或用 `deploy/backup` 的库恢复到新环境
4. **注册非幂等**：重复提交由前端「单飞」控制 + 数据库唯一索引兜底（见 §8 第 15 条），
   后端不提供幂等键。
5. **没有长期在线部署**：本项目按「本地为主 + 按需上云」设计（§1），日常记账在本地，
   云端只在**试用期 / 面试期**开启。原因：长期租用轻量服务器约 100–200 元/学期，本人不接受。
   代价：出门在外不能即时记账，靠事后补录（`transDate` 是业务日期，统计不受影响）；
   简历上写「按需部署于轻量应用服务器」，不承诺 7×24 在线。
   如果哪天需要零成本长期在线，备选是「常开电脑 + Cloudflare Tunnel」，但可靠性取决于那台机器与网络。
6. **公示链接是「单链接 + 3 天」模型**（§5.9）：同时只有一个有效 token，再分享即让旧链接失效，
   适合「发一次、看几天」的班费公示；代价是**无法同时给两个群发不同链接**（第二个群会顶掉第一个）。
   链接泄露时点一次「分享」就能废掉旧的，不用登服务器改库。
   过期链接仍留在 `book.share_token` 里（只是 `share_token_expires_at` 已过去），不影响唯一索引。
7. **回收站清理依赖应用在跑**：到期清理靠每天 3:30 的 `@Scheduled`，停机期间不会执行；
   补偿是 `GET /api/books` 时顺手清理一次，所以**长期不打开应用就不会清理**——数据留着比误删好。
   想立刻清理：`SELECT id FROM book WHERE deleted = 1 AND deleted_at < NOW() - INTERVAL 15 DAY;`
   然后按「流水 → 分类 → 账本」手工删，或删完 `deleted_at` 后重启应用一次。
   另外 `deleted_at IS NULL` 的历史已删账本（加这个字段之前删的）永不自动清理，需要时手工补时间。

## 10. Backlog（已记下、还没做；动工前先把契约和测试补齐）

### 10.1 用户管理 ✅ 已完成（2026-11-06）

三个接口与 `/settings` 页已落地：`UserService` / `UserController` / `UserManageApiTest`（5 例）+ 前端
`views/SettingsView.vue`。原计划的四条必测点都在 `UserManageApiTest` 里：旧密码错不改、改完旧 token 401、
注销后同名可注册且账本进回收站、注销密码错什么都不删。

实现备注（给以后的自己）：

- 改密码用 `setSql("token_version = token_version + 1")` 原子自增，读出来 +1 再写会丢并发
- 注销顺序是「先软删账本 → 再软删 user」，同一事务；账本的 `deleted_at` 必须写，否则 15 天清理永远选不到
- `BookMapper.softDeleteAllByUser` 是手写 `@Update`（一次改多行 + 自己写 `deleted_at`），不要换成 Wrapper
- 前端改密码/注销成功后必须 `auth.logout()` + 跳登录：服务端已经把 token 作废了，停在原页面只会 401

### 10.2 邮箱找回密码 ✅ 已实现（QQ 邮箱 SMTP）

发信通道：**QQ 邮箱 SMTP**（`smtp.qq.com:465`，SSL，用授权码而不是登录密码）。
`--reset-password` 命令行入口已删除，邮箱是唯一重置通道。

凭据与参数（全部走 `deploy/env.ps1`，绝不入库）：

```
MAIL_USERNAME=你的QQ邮箱
MAIL_PASSWORD=SMTP 授权码
MAIL_FROM=班级记账 <你的QQ邮箱>
APP_BASE_URL=http://你的访问地址（拼重置链接用）
```

数据表 `password_reset_token`：`user_id / token_hash(sha256) / expires_at / used_at`；
**库里不存明文 token**，一次性、15 分钟有效，重新申请会把同用户其它未用 token 一起作废。

接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/forgot-password` | `{email}`；已注册才发信，**永远** `code=200` 同一句提示 |
| POST | `/api/auth/reset-password` | `{token, newPassword}` → 校验未过期未使用 → 改密码 + `token_version + 1` |

限流（进程内，`support/SimpleRateLimiter`，W4 换 Caffeine 时整块替换）：

- 同一邮箱：3 次/小时
- 同一 IP（优先 `X-Real-IP`）：10 次/小时
- 超限**不发信但照旧返回 200**，只记 WARN——不能让限流本身变成探测手段

测试（`PasswordResetApiTest`，`@MockBean` 掉 mailer，不真发信）：
未知账号信息不发信、正常发信、token 用过即废、过期失效、限流第 4 次不发信、重置后旧 token 401。

### 10.3 导出 / 导入 Excel 与导入 CSV（明日事项）

现状只有导出 CSV（`GET /api/transactions/export`）。要补三件事：

| 方向 | 格式 | 说明 |
|---|---|---|
| 导出 | `.xlsx` | 列与 CSV 一致：日期 / 类型 / 分类 / 金额 / 备注 / 逐行结余；金额仍是两位小数字符串，日期 `yyyy-MM-dd` |
| 导入 | `.csv` | 列：`日期,类型,分类,金额,备注`；类型用 `income/expense`（或「收入/支出」二选一先在服务端兼容） |
| 导入 | `.xlsx` | 与 CSV 同一套解析规则，只是读取方式不同 |

实现要点（动手前先定）：

- 后端加 POI 或 EasyExcel（二选一，注意依赖体积）；CSV 解析别手写引号，用 commons-csv
- 接口 `POST /api/transactions/import`（multipart）：先**全部校验**再写库，返回 `{success: n, failed: [{line, reason}]}`；
  部分成功 vs 全量回滚要明确——建议「行级失败不阻塞其它行」，但**同一次请求仍在事务里**，失败行只返回原因
- 分类不存在怎么办：先报错（引导用户先建分类），不做「自动建分类」，否则导入一份脏文件会长出一堆脏分类
- 文件限制：≤5MB、UTF-8（含 BOM）并兼容 GBK；空行跳过
- 前端：导出加「Excel」选项；导入给模板下载 + 失败明细表

### 10.4 自定义分类模板（明日事项）

需求原话：**在「记一笔」里选择「其他」时，应当就在当前弹窗出现输入框，输入新分类名并直接应用**，不用跳去分类管理页。

现状：`/api/categories` 已有增删改查（PLAN §5.4），缺的只是「记一笔」里的就地创建入口。

约定：

- 分类下拉里「其他」之后加一行「＋ 新建分类」，点开显示输入框 + 「新增并使用」
- 提交后调 `POST /api/categories`（type 取当前记账的收支方向），成功后把新分类插进下拉并选中，不关闭「记一笔」弹窗
- 同账本内分类名重复 → 后端已有 400，前端提示并保留输入
- 删除分类有流水时 400（§8 第 9 条），不要级联删流水
