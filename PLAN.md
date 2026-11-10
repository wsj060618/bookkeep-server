# 记账网站后端开发计划书（bookkeep-server）

## 1. 项目信息

- 后端位置：`D:\coding\bookkeep-server`（本人开发）
- 前端位置：`D:\coding\bookkeep-web`（**由 AI 编写**，本人负责契约定义与联调验收；严格按本计划书接口契约，硬约定见 §5.0，配合节奏见 §6.1）
  - 技术栈（第 7 天定）：Vue 3 + **TypeScript** + Vite + Element Plus + Pinia + vue-router + axios
  - 选 TS 的理由：接口字段写错在 `npm run build`（`vue-tsc`）就报错，而不是等联调；代价是本人要先过一遍 TS 基本概念（`bookkeep-web/README.md` 有速查）
  - 独立 git 仓库；`src/types/api.ts` 是后端 DTO 的镜像，**后端改 DTO 必须先改它**
- 技术栈：
  - Spring Boot 3.5.x + JDK 17 + Maven
  - MyBatis-Plus（`mybatis-plus-spring-boot3-starter`）+ MySQL 8
  - Lombok + spring-boot-starter-validation
  - Spring Security + JWT（jjwt 0.12.x）
  - **AI 边车（可选，§10.8）**：Python 3.12 + FastAPI + LangChain + **LangGraph**；
    只监听 `127.0.0.1:8000`，不对外、不连数据库，Java 侧 `RestClient` 调它并自带降级；
    模型走**用户自定义的 OpenAI 兼容 API**，`AI_BASE_URL`/`AI_MODEL`/`AI_API_KEY` **默认全部为空**（为空即关闭）
- 工程结构：**Maven 父子工程（多模块）**，父工程 `bookkeep-server` 下拆四个子模块（`common` / `model` / `service` / `web`，见 §4）
- 部署形态（第 7 天定稿，一套代码两种部署目标，差异只在配置）：
  - **本地单机（日常使用，主目标）**：Windows + 本机 MySQL 8 + Nginx for Windows 托管前端静态文件，
    并反代 `/api` → `127.0.0.1:8080`；后端只监听回环地址（不对外端口），Nginx 监听 80（局域网可访问）
  - **云端（演示与取证，按需开启）**：Docker Compose 编排 `mysql` + `app` + `nginx`，部署到轻量应用服务器；
    **只在试用期 / 面试期开启**，不做长期租用（理由与成本见 §9）
  - **云端没有 HTTPS，是刻意接受的**：国内云服务器的域名**必须备案**才放行，裸 IP 又签不到可信证书
    （Let's Encrypt 不给 IP 签；自签证书在手机上人人弹警告）。所以云端是 `http://服务器IP/`，
    `docker/nginx.conf` 只监听 80、不加 HSTS；补偿手段是登录限流 + `JWT_EXPIRE=86400` +
    3306/8080 不 publish + 只开 80。要上 HTTPS 只有「备案域名」或「境外服务器」两条路，
    那时换 `docker/nginx.tls.conf`（compose 的 `NGINX_CONF` 切换），代码不用动
  - 配置差异：本地 `application-local.yml`（gitignore），云端走环境变量；`nginx.conf` 两边共用，只改 `root` 与 `proxy_pass`
- 部署边界：**不做**前后端两个端口直连（会引入 CORS），一律「Nginx 同源托管 + 反代」，后端只出 JSON

## 2. 核心设计

> **场景定稿（第 7 天）**：这是一个给**生活委员记班费**的自用系统，单用户、单账本。
> 原计划里的个人版 / 商家版、多账本、预算、AI 分析、头像上传全部**有意砍掉**（原因见 §6 的范围取舍），
> 目的是一个月内做出「能真实用的系统 + 能讲的简历项目」。

### 2.1 多账本（类型仍只有 class）
- `book.type` 固定 `class`；一个用户可有多本账本（上限 10 本），同一时刻只有一本是**当前账本**
- 账本增删改查齐全：列表 / 新建（选一套分类模板来预置分类）/ 改名 / 切换当前 / 删除（进回收站）
- 删除 = 进回收站，**15 天内可恢复**；到期连同该账本的分类与流水彻底删除（§5.2）
- **至少保留一本**：删到最后一本返回 400，否则分类/流水/统计接口会全部「没有当前账本」
- 注册即建第一本账本并预置 **11 条班级分类**（7 支出 + 4 收入，见 §3）
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
| 个人 / 商家账本与模板 | 场景只有一个：班费。多类型会带来无用的统计分支（**2026-11-07 变更：通用模板回归，见 §10.6**） |
| 预算 | 班费没有「每月预算」概念 |
| AI 分析（规则引擎） | 名不副实、面试容易被问穿，且对班费无用 |
| 头像上传（OSS） | 单用户自用，无意义；也避免引入 AK/SK 与外部依赖 |
| 多用户共享账本 | 需要 `book_member` + 角色 + 全接口改权限校验（3–5 天），班费场景收益低 |
| 短信找回密码 | 需要短信通道（实名 + 按条计费）；邮箱一次性 token 已够用（§5.1） |

> **多账本管理原先在这张表里，后来做回来了**（需求变更）：现在支持列表 / 新建 / 改名 / 切换 /
> 删除（回收站 15 天），见 §2.1 与 §5.2。仍然不做的是：多人共享账本、账本排序自定义。
> **通用记账模板也回归了**（2026-11-07）：`book.type` 允许 `class|general`，新建账本默认 `class`，
> 详见 §10.6；「个人/商家多类型 + 各类型独立统计分支」仍然不做。

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

-- 预算表（budget）已移出范围并删除（见 §2.4）：不建表。
-- 如要恢复：`(id, book_id, category_id, month 'YYYY-MM', amount DECIMAL(12,2), created_at, updated_at)`
-- + UNIQUE(book_id, category_id, month)；无 deleted 列（物理删除，否则唯一键会和"删了再建"冲突）。
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
| POST | `/api/auth/register` | 注册并创建班级账本（预置 11 条分类） | `{username, password, email, bookName?}` → 用户信息。**邮箱是登录凭据**：必填且未删除行内唯一；`username` 只是显示名；**没有 bookType / nickname**；`bookName` 为空默认「班级账本」 |
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
| POST | `/api/books` | 新建 `{name, templateId?}`：按模板预置分类（`templateId` 省略 / 0 = 内置 11 条），并自动切为当前 |
| PUT | `/api/books/{id}` | **修改账本** `{name}`（只有改名；已有 `shareToken` 不变） |
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
| POST | `/api/categories` | 新增 `{name, type, icon?, color?}`；同方向不能重名 |
| PUT | `/api/categories/{id}` | 修改（仅自己账本；`type` 不可变） |
| DELETE | `/api/categories/{id}` | 删除（仅自己账本；若已有流水则业务异常提示） |

### 5.4.1 分类模板（跟账号走，2026-11-10 增补）

模板是**一整套分类清单**，属于账号而非账本：一个账号可以有多套模板，新建账本时选一套，用它预置那本账本的分类。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/category-templates` | 模板库：内置「班级班费」排第一（`id=0`、`builtin=true`），之后是自建模板，条目一并返回 |
| POST | `/api/category-templates` | **从零新建** `{name, items:[{name,type,icon,color}]}`；`sortOrder` 由后端按顺序编号 |
| POST | `/api/category-templates/from-book` | 把**当前账本**的分类快照成新模板 `{name}`；不接收条目 |
| PUT | `/api/category-templates/{id}` | 改名；带 `items` 时**整体替换**条目（内置模板 400） |
| DELETE | `/api/category-templates/{id}` | 物理删除；引用它的账本 `categoryTemplateId` 清空（回落到内置） |
| POST | `/api/category-templates/{id}/apply` | 应用到当前账本（`id=0` = 内置）；**账本有流水时 400**。界面已不露出，保留备用 |

- 内置模板**不入库**：内容取自 `CategoryPresets.classCategories()`，与注册时预置的 11 条同源，`book.category_template_id` 为 `NULL` 即表示内置
- **新建账本就选模板**：`POST /api/books` 的 `templateId`（省略 / 0 = 内置）决定这本账本预置哪套分类，`book.category_template_id` 一并写入。这是唯一的主力入口——建好之后再换模板要满足「没有流水」，所以模板应该在新建时一次选对
- 模板条目在「新建模板 / 编辑模板」弹窗里维护（支出、收入两张卡片，右上角新增，可改名/换图标颜色/删除），一次提交整批替换
- 应用 = 逻辑删除旧分类 + 按模板条目逐条新建；分类是**复制**不是引用，删模板不影响已套用的账本
- **「其他」不是分类也不是模板条目**：它只是「记一笔」下拉末尾的虚拟入口，选中就弹同一个分类表单（可挑图标/颜色），所以内置模板从 13 条减到 11 条

### 5.5 流水（按当前账本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/transactions?page=1&size=10&type=&categoryId=&startDate=&endDate=&keyword=` | 分页查询 → PageResult |
| POST | `/api/transactions` | 新增 `{categoryId, type, amount, transDate, note?}`；**支出不能超过当前余额** |
| PUT | `/api/transactions/{id}` | 修改（仅自己账本，同样校验余额） |
| DELETE | `/api/transactions/{id}` | 删除（仅自己账本） |
| GET | `/api/transactions/export?startDate=&endDate=` | 导出 CSV（**流式**，含逐行结余），换届与公示用；参数错误仍是 JSON 信封 |

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
- 统计口径：本月收入/支出/结余；累计结余 = 全账本 `sumBalance`（不等于本月结余）；分类占比按 `GROUP BY category_id`，
  **`type` 可选 `expense` / `income`**（默认 expense）——支出、收入各看各的占比

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
| GET | `/api/statistics/category?month=&type=expense` | 分类占比 `[{categoryId, categoryName, categoryIcon, categoryColor, amount, percent}]`；`type` 传 `income` 就是收入占比 |

- `month` 不传取当月（北京时间），格式必须是 `yyyy-MM`，格式错返回 400 而不是空数据（空数据会被误读成「这个月没记账」）
- `percent` 保留两位小数、四舍五入，**各项相加可能不等于 100.00**（例：三项各 33.33），前端别用它反算金额
- 分类项带上 `categoryId/icon/color` 是为了饼图图例直接出图标（§5.0 第 5 条），不是允许前端再查分类
- 统计口径只在 `StatisticCalculator` 实现一次：登录态统计与免登录公示页共用，避免两处数字不一致
- 前端展示形式可切换：**柱状图 / 饼图 / 环形图**，三者**都用 ECharts 渲染**（同一套组件，切图形只换 series，
  标尺留白与配色才一致）；ECharts 按需引入并懒加载，只落在真正画图时才拉的 chunk 里
- 占比区**收入在左、支出在右并排一行**（≥900px 两栏，窄屏堆叠），各带本方向合计——不再用「支出/收入」切换按钮
- 柱状图是**竖条形**（x 轴 category、y 轴 value）：分类超过 5 个时 x 轴名字斜 32°，金额标签只在 ≤6 条时显示
- 饼图/环形图的文字不硬挤：分类超过 8 个时不挂扇区标签，只留图例 + tooltip；8 个以内也用
  `labelLayout.hideOverlap` 自动隐藏重叠标签（官方示例的常规做法）

月度趋势 / 每日趋势 / 利润 / 成本结构**不做**（图表的边际价值低于部署与导出；需要时再加）。

### 5.7 预算

**不做**（见 §2.4）。建表语句已从 `schema.sql` 删除（`sql/migration_drop_budget.sql` 负责删掉老库里的空表），不建实体、不写接口。

### 5.8 AI 分析

**不做**（见 §2.4）。原「规则引擎 + level 预警」的设计整体放弃。

### 5.9 只读公示页（免登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/books/share` | 生成**一条**公示链接 `{ids:[1,2]}` → `{token, expiresAt, books:[{id,name}]}`；一条链接可含多本账本 |
| POST | `/api/books/{id}/share` | 单本分享（等价于只选一本），返回 `{token, expiresAt}` |
| GET | `/api/public/report/{shareToken}?month=2026-08` | 免登录只读：链接里**每本账本**的收入/支出/结余 + 支出与收入两份分类占比 + 明细（含逐行结余） |

响应体：`{expiresAt, books:[{bookId, bookName, month, income, expense, balance, totalBalance, expenseCategories:[…], incomeCategories:[…], transactions:[{transDate, type, amount, note, categoryName, categoryIcon, categoryColor, balanceAfter, receiptCount, receipts:[{id, title}]}]}]}`

> 分类占比给**两个方向**：`expenseCategories`（钱花哪了）与 `incomeCategories`（钱从哪来），
> 两边的口径都走 `StatisticCalculator`，与登录态统计接口同一份。公示页**收入在左、支出在右并排**，
> 图形可切柱状图 / 饼图 / 环形图（与统计页同一个组件）。

> `receiptCount` / `receipts` 是 §10.9 公示票据的预留字段，票据功能落地前不返回（或恒为空）；图片本身不在 JSON 里，见 §10.9「公示页票据」。

- 链接是独立实体：`share_link`（token / 到期时间 / 归属用户）+ `share_link_book`（链接 ↔ 账本的多对多），
  **不再挂 `book.share_token`**。原因：同学只想发一条链接就能同时看到几本账本
- **每个账号最多一条有效链接**：`POST /api/books/share` 先删旧链接（含成员）再建新的，
  **每次分享都换新 token**，旧链接立即失效——泄漏时再点一次「分享」就能掐掉它
- 查询条件必须同时带 `token = ?` 和 `expires_at > now()`：过期、从未分享、成员全被彻底删除
  一律 `code=404「公示页不存在或已失效」`，**绝不能回落到「当前登录用户的账本」**
- 账本进回收站时**不动**成员关系（恢复后链接照旧能看）；账本**彻底删除**时移除成员，
  成员被清空的链接一并删掉（`ShareLinkMapper.deleteOrphanLinks`）
- 明细按日期**升序**返回：逐行结余只有从上往下读才成立，翻页/倒序展示由前端处理
- 明细不带 `id`、整页不含 `username`/`email`/`userId`：这个接口匿名可访问，能少暴露一个字段就少一个
  （票据只带 `receipts[].id`，那个 id 只在本链接内可用，见 §10.9「公示页票据」）
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
  分类合计；期初/期末结余按区间的边界日计算；成功返回文件流，参数错误仍走 JSON 信封。
  **流式**：`prepare()` 先校验+取数（此时响应未提交，出错还是正常信封），之后 `StreamingResponseBody`
  逐行写出去，不再把整份 CSV 拼成 `byte[]`
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
36. **Nginx 配置不能带 UTF-8 BOM**：Windows PowerShell 写文件很容易带上 BOM，nginx 会把 BOM 当成
    第一个指令的一部分，报 `unknown directive "﻿"`（且行号指向第 5 行，极具误导性）。
    改完 `deploy/nginx/nginx.conf` 后用 `UTF8Encoding($false)` 重写，或先跑 `nginx -t`
37. **`add_header` 不继承**：`location` 里只要出现任何一条 `add_header`，`server` 级的安全头就全部失效。
    `/index.html`（Cache-Control）和云端各 location 都必须把安全头再写一遍，否则「看起来配了其实没生效」
38. **Docker 里后端必须监听 0.0.0.0**：默认 `server.address=127.0.0.1` 只适合 Windows 直跑；
    compose 里用 `SERVER_ADDRESS=0.0.0.0` 让 nginx 容器能访问，同时**绝不能 publish 8080**，
    否则回环隔离就白做了
39. **POI 图表：数据放隐藏区会整条序列消失**。`<c:plotVisOnly val="true"/>`（POI 默认）表示
    「只画可见单元格」，数据源一旦放在隐藏 sheet **或隐藏列**里，Excel/WPS 直接丢掉整条序列——
    表现是只剩坐标轴和图例、柱子全是空的，而且不报错。必须 `chart.setPlotOnlyVisibleCells(false)`。
    同一个坑还有三处：`addSeries()` 不给序列名会写空的 `<c:tx/>`（同样被判无效）；
    柱状图缺 `<c:grouping>`（POI 的 XDDF 工厂不写）；环形图不写 `<c:holeSize>` 会渲染成实心饼。
40. **`XSSFFont.setFontHeight(short)` 的单位是 1/20 磅**：想要 8 磅要传 160，写 16 就成了 0.8 磅——
    标题在 Excel 里缩成一条虚线，肉眼几乎看不见。要用 `setFontHeightInPoints((short) 14)` 这种带单位的写法

## 9. 已知缺口（P1 有意不做，上线前必须回看）

1. **限流 ✅ 已实现（2026-11-07，公网前置条件）**：`support/AuthThrottle`（Caffeine，进程内）：
   - 同一 `IP + email` 连续失败 **5 次锁 5 分钟**，登录成功清零；单 IP **30 次/分钟**登录请求上限
   - 单 IP **5 次/小时**成功注册上限（防批量注册）
   - 严格值只在 `application-prod.yml`；开发/局域网默认放宽到几乎不限
   - Nginx 侧对 `/api/auth/(login|register|forgot-password)` 另加 20 次/分钟（防绕过应用层）
   - 仍属**单实例内存**计数：多副本部署需换共享存储；`X-Real-IP` 已透传（§8 第 21 条）
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

> **执行顺序（同一功能放一块，2026-11-07 调整）**
>
> **阶段 0：先把部署做完（W4）** —— 其余功能都在部署之后。
>
> 1. **账本口径簇**：§10.6 通用模板 → §10.5 学期归档 ✅（10.5 已完成）
>    - 两者都改 `book` 表、`BookResp`、以及「新建账本 / 修改账本」同一个弹窗，连着做只需要改一次前端表单与一次回归
> 2. **分类与模板簇**：§10.4 分类管理页 + 记一笔就地新建 → §10.7 模板 JSON 导入导出
>    - 10.7 的按钮就挂在 10.4 新建的页面上，且都要用同一个 `reloadCategories()`；先做 10.4 等于把地基打好
> 3. **流水簇**：§10.3 流水导入导出（xlsx / CSV）
>    - 唯一不依赖前两项的独立模块，工作量也最大，放最后整块做；时间够也可以提前单独做
> 4. **票据簇**：§10.9 票据上传/命名/按账本月份存储/导出 + 公示页只读票据
>    - 一次做完，别只做上传不做公示页：公示页票据复用了同一张表、同一套魔数校验，拆开做要回归两遍
> 5. **拓展（部署稳定之后）**：§10.8 AI 功能（**Python 边车：FastAPI + LangChain + LangGraph**；一句话记账 → 月度综述 → 智能分类建议）
>
> 理由：按「数据表 + 页面」归堆，避免同一张表/同一个弹窗被来回改三次、测试写三遍；
> AI 属于加分项，必须等部署与分类体系稳定后再做，否则评测和输出校验都要返工。

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

### 10.3 导出 / 导入 Excel 与导入 CSV ✅ 已完成（2026-11-10）

**导出**：`GET /api/transactions/export?format=csv|xlsx`（默认 csv）。
`format=xlsx` 是真正的 Excel（Apache POI 5.4.1），版式对齐真实班费表：可见 sheet「收支明细」
顶部左侧是 `收入 / 支出 / 结余` 三行彩色汇总（A:B 标签 + C 金额），右侧 D..G 四列里
**柱状图与环形图并排一行、各占一半宽度**（竖柱三色：收入绿 `#10B981` / 支出红 `#EF4444` / 结余蓝 `#409EFF`，
与网页端同一套语义色；环形只比收支），
整个头部与表格同宽、左对齐；第 13 行是单行表头 `序号 | 日期 | 摘要 | 收入 | 支出 | 班费结余 | 备注`，第 14 行起是明细。
图表数据放在**同表隐藏的 J/K 列**（不能放隐藏 sheet：Excel 会丢数据源）。**刻意不做数量 / 单价 / 人均缴纳**：系统只存金额，编造数量会误导看表的人。
文件名 `{账本名}_全部` / `{账本名}_部分_{起}-{止}`。两种格式共用 `TransactionExportService.prepare()`，
口径不会分叉；仍是**流式**输出（`StreamingResponseBody`，校验在写响应之前完成，失败仍返回 JSON 信封）。

**导入**：`POST /api/transactions/import`（multipart，**只收 `.csv`**）→ `{success, failed:[{line, reason}]}`。

- 列固定：`日期, 类型, 分类, 金额, 备注`；类型认「收入/支出」与 `income/expense`；UTF-8（含 BOM 也认）
- **行级失败不阻塞其它行**：成功行入库，失败行带**文件行号**（1 基，含表头，和 Excel 左侧一致）与原因
- **分类不存在直接报错，不自动建分类**；支出仍受余额约束（按文件顺序累加余额）
- 限制：≤5MB（`spring.servlet.multipart.max-file-size`）、≤2000 行；空文件 / 只有表头 / 超限整份 400
- 归档账本只读：导入前先拦，返回与记账相同的 400
- **只收 CSV**：`.xlsx` 直接 400（提示先另存为 CSV UTF-8）——Excel 版式千人千面，强行解析只会满屏报错
- **自动找表头**：扫描「日期」开头的那一行，跳过导出 CSV 里的标题 / 汇总；
  同时认两种列结构——标准 `日期,类型,分类,金额,备注` 与本系统导出的 `日期,分类,摘要,收入,支出,结余`
  （按收入/支出哪列有值判断方向），所以**导出 CSV → 导入可原样转回**
- 前端：导出改成下拉（CSV / Excel）、导入弹窗（选文件 + 下载模板 + 失败明细表），
  导入有成功行时 `notifyTxChanged()` 让列表与统计页一起刷新

> 遗留：CSV 只按 UTF-8 解析；Excel 另存的 **GBK 编码 CSV** 会乱码（需要时再加编码探测）。
> 导入是「全有或全无」的近似——常见错行不影响其它行，但整个请求仍在一个事务里。

### 10.4 自定义分类模板 ✅ 已完成（2026-11-10）

三件事一起做：**①「记一笔」里就地新建分类并应用；② 单独开一页管当前账本的分类；③ 多套分类模板 + 下拉切换。**

> **口径变更（2026-11-10，第二版）**：第一版只做了「在默认 13 条分类上增删改」，用户指出要的是**多套模板 + 下拉切换**。
> 于是追加了「模板」这一层，并确认了三个边界：
> - **模板跟账号走**（`category_template.user_id`），一套模板可应用到该用户的任意账本；不是每本账本各有一套
> - **只允许没有流水的账本换模板**：换模板 = 分类整套换掉，已有流水的分类归属会断，宁可 400 也不悄悄迁移流水
> - **只内置「班级班费」一套**（即注册预置的 13 条），其余全部用户自建
>
> **口径变更（2026-11-10，第三版，最终）**：用户看完第二版后要求改交互——
> - **新建账本改成弹窗**（起名 + 选分类模板），不再在账本页内联一行
> - **模板自己新建**：分类页加「新建模板」按钮，弹窗里支出/收入两张卡片、右上角新增条目，条目可改名/换图标颜色/删除；「存为模板」（快照当前账本）保留
> - **「应用到当前账本」按钮撤掉**（后端 `apply` 接口保留备用），那个位置换成「新建模板」
> - **「记一笔」选「其他」改为弹窗新建分类**（和分类页同一个表单，能挑图标/颜色），不再在下列表下面挂输入行
> - **因此内置模板去掉两条「其他」**（13 → 11 条）：既然「其他」是现建入口，就不该再是一条真实分类
>
> 落地记录：
> - 数据库：`category_template` + `category_template_item`（都物理删除，无 `deleted` 列）+ `book.category_template_id`（`NULL` = 内置）。迁移脚本 `sql/migration_category_template.sql`
> - 内置模板**不入库**、id 为 `0`，内容取 `CategoryPresets.classCategories()`（原 `constant/CategoryTemplate` 改名而来，避免与实体 `CategoryTemplate` 撞名）——注册预置的分类与内置模板永远同源
> - 模板条目接口：`POST /api/category-templates`（从零建，带 items）、`POST /api/category-templates/from-book`（快照当前账本）、`PUT /{id}`（改名，带 items 则整体替换）；`sortOrder` 由后端按数组顺序编号，前端固定先排支出再排收入
> - 应用模板 = 逻辑删除旧分类 + 按条目逐条新建 + 回写 `book.category_template_id`；分类是**复制**不是引用，所以删模板不影响已套用的账本（删模板时把引用它的账本 id 清空，含回收站里的账本，走手写 SQL `BookMapper.clearTemplateRef`）
> - 注销账户同步物理删除该用户的模板与条目（`BookMapper.hardDeleteTemplate*ByUser`），否则留下孤儿数据
> - **新建账本时选模板**：`POST /api/books` 增加 `templateId`（`BookCreateReq`，省略 / 0 = 内置），账本页「＋ 新增账本」弹窗里选，默认沿用当前账本的模板——建好之后再换模板要求「没有流水」，所以模板应该在新建时一次选对
> - 前端共用组件：`components/CategoryFormDialog.vue`（名称 + 图标 + 颜色，**不带收支方向选择**，方向由场景固定：支出卡片里新增就是支出、记账按当前方向），被分类页、模板弹窗、「记一笔」三处复用；`components/TemplateFormDialog.vue` 是模板新建/编辑弹窗
> - 「记一笔」：分类下拉末尾固定一个虚拟「其他」（值是哨兵 `-1`），选中即打开同一个分类表单，建完自动选中并继续记账；`emit('category-created')` → layout 先插缓存再重拉
> - **拖拽排序：不做**（用户确认「顺序无所谓」）。`sort_order` 仍由后端新增时自增，没有 `PUT /api/categories/sort`
> - **分类重名**：原 PLAN 假设「后端已有 400」，实际没有——补上 `CategoryServiceImpl.requireNameAvailable`，按 `(book_id, type)` 判重（支出、收入各一条同名是允许的，所以 type 必须在 key 里），新增与改名都拦。没有 DB 唯一索引，属于**应用层友好提示**，并发下仍可能插进两条同名。模板条目同样按方向判重
> - **老数据**：「其他」只是从预置里去掉；2026-11-10 之前建的账本用一次性脚本 `sql/migration_drop_other_category.sql` 清理（**只删没有流水引用的**，有流水的保留，绝不丢账目归属），跑完按新口径只剩 11 条
> - 流水页筛选：分类在别的页面被删后，`filterCategoryId` 指向不存在的 id 会让列表永远空白，已加 `watch(shell.categories)` 兜底清空并重查

#### ① 记一笔里现建分类

需求原话：**在「记一笔」里选择「其他」时弹出弹窗新建分类，要能选图标，和分类的新建一样**。

- 分类下拉末尾固定一条「📌 其他（新建分类…）」，选中它不会真的被选上（v-model 回退到上一个分类），而是打开分类表单弹窗
- 提交后调 `POST /api/categories`（type 取当前记账的收支方向），成功后把新分类插进下拉并选中，不关闭「记一笔」弹窗
- 同账本同方向分类名重复 → 400，前端提示、弹窗保持打开
- 删除分类有流水时 400（§8 第 9 条），不要级联删流水

#### ② 独立的分类管理页（路由 `/categories`）

分类是「按账本」的数据，散在「记一笔」下拉里管不了，单独开一页：

- **导航**：AppLayout 侧栏加「🏷 分类」，顺序放在「账本」之后、「设置」之前（手机抽屉同步）
- **页面结构**：顶部模板条（下拉 + 新建模板 / 存为模板 / 编辑 / 删除）；下面按 `支出 / 收入` 分两组，每行显示 图标 + 名称 + 颜色条 + 操作（改名 / 换图标颜色 / 删除）
- **接口**：直接复用 §5.4 的 4 个（`GET/POST/PUT/DELETE /api/categories`）；
  排序（`sort_order`）若要支持拖拽，需要补一个 `PUT /api/categories/sort`（收 id 数组，按顺序重写 sort_order）——已确认不需要拖拽，未实现
- **图标与颜色**：库里存的是语义 key（`icon` / `color`），页面给固定候选集让用户点选，不引图标库
- **一致性**：页面增删改后，「记一笔」的下拉来自 layout 的 `categories` 缓存，必须同步刷新——
  给 `ShellApi` 加一个 `reloadCategories()`（或在 `useShell` 里暴露 `categoryVersion`），照 `txVersion` 的现成模式做
- **边界**：至少保留一条分类？不强制，但删到 0 条时「记一笔」没法选，页面给空状态提示「先新增一个分类」

### 10.5 按学期分离 ✅ 已完成（2026-11-10，方案 A：一个学期 = 一本账本）

班费天然按学期结算（秋季/春季），原来的数据模型只有一个连续账本，跨学期混在一起。做法：给账本补学期元数据，学期结束「归档」成只读。

**接口（与实际实现一致）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/books` | `{name, templateId?, semesterEnd?}`（`semesterEnd` 为 `yyyy-MM-dd`，可空） |
| PUT | `/api/books/{id}` | `{name, semesterEnd?}`（改名 + 改截止日期一次提交；**`semesterEnd` 省略 = 不改**，不做「清空」语义） |
| PUT | `/api/books/{id}/archive` | 归档成只读；**幂等** |
| PUT | `/api/books/{id}/unarchive` | 取消归档；**幂等** |
| GET | `/api/books` | `BookResp` 增加 `semesterEnd`、`archived` |

**落地记录**

- 迁移 `sql/migration_semester_archive.sql`：`book.semester_end DATE NULL` + `book.archived TINYINT NOT NULL DEFAULT 0`
- **只读统一在一处判**：`CurrentBookService.writableBookId()`（读仍用 `currentBookId()`）。
  `TransactionService`（新增/修改/删除流水）、`CategoryService`（新增/改名/删除分类）、
  `CategoryTemplateService.apply` 全部换成它，漏一个就会出现「界面禁了、接口能写」
- 归档仍允许：改账本名、改学期截止日期、切换当前账本、删除进回收站、取消归档、**导出**、**公示页**
- 前端：建账本弹窗加「学期截止日期」；账本页「改名」升级成「编辑」弹窗（名字 + 截止日期）、
  列表显示「已归档」徽标与「截止 yyyy-MM-dd」；当前账本归档时 layout 顶部出只读横幅、
  「＋ 记一笔」收起、流水行隐藏编辑/删除、分类页三个写按钮禁用
- 测试 `BookArchiveApiTest`（5 例）：建账本带截止日期、归档后四类写操作全 400 且读与导出照常、
  归档后仍可改名/改截止日期且不丢归档状态、取消归档即可记账、别人的账本不能归档
- 上学期结余仍是**手工记一笔「结余转入」**，不做自动搬账；跨学期汇总仍不做
- 历史账本 `semester_end` 允许 NULL，列表显示为空，不做数据回填

### 10.6 通用记账（班级模板为默认，明日事项）

定位调整：**项目从「班级记账」放宽为「通用记账」，班级模板只是默认选项**。

#### 模板与数据

- `book.type` 复用为**模板 key**：`class`（默认）| `general`（通用）
  - 列已经存在（`VARCHAR(10) NOT NULL DEFAULT 'class'`），**不需要迁移**；历史账本全都是 `class`
  - 语义从「账本类型」改成「初始分类模板」，统计/导出/公示都不感知它
- `CategoryTemplate` 增加 `generalCategories()`（与 `classCategories()` 并列）：

| 方向 | 通用模板分类 |
|---|---|
| 支出 | 餐饮、交通、购物、居住、娱乐、医疗、其他 |
| 收入 | 工资、红包、理财、其他 |

- 注册仍然建 `class` 账本（默认不动），所以老用户体验不变

#### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/books` | `{name, semesterEnd?, template?}`；`template` 缺省 = `class`，非法值 400 |
| GET | `/api/books` | `BookResp.type` 返回 `class` / `general`（前端据此显示模板标签） |

#### 前端

- 「新建账本」弹窗加模板下拉：**班级（默认）/ 通用**，各带一行说明
- 账本页每行显示模板标签（班级/通用），「修改」弹窗**不允许**改模板（改模板等于换一整套分类，容易毁数据；真要换就新建）
- **品牌文案改成中性**：`index.html` 标题、登录页、侧栏品牌、分享页文案里的「班级记账 / 班级账本」去掉「班级」描述（登录后仍显示具体账本名）

#### 测试

- 不传 `template` → 13 条班级分类（8 支出 + 5 收入）
- `template=general` → 11 条通用分类，且与班级模板的 id/名称不同
- `template=xxx` → 400；`book.type` 落库正确

#### 不做

- 跨账本复用「自定义模板」（另存为模板 / 模板市场）：先只有内置两套 + 明天的分类管理页
- 各模板独立的统计分支：统计算法保持现状，分类只是数据

### 10.7 分类模板的导入 / 导出（JSON，明日事项）

不做模板表：**「模板」= 一份 JSON 文件 + 当前账本的那组分类**，导出的就是账本分类，导入就是往账本里建分类。简单、无迁移。

#### 文件格式

```json
{
  "version": 1,
  "name": "我的生活记账",
  "items": [
    { "type": "expense", "name": "餐饮", "icon": "dining", "color": "orange", "sortOrder": 1 },
    { "type": "income",  "name": "工资", "icon": "fee",    "color": "green",  "sortOrder": 9 }
  ]
}
```

- `name`：模板名，**可编辑**（内置的「班级 / 通用」只是生成规则，导出时默认给 `{账本名} 分类`，用户可以改名再导出/分享）
- `items`：按 `type` 分成 **支出 / 收入** 两组展示与校验
- `icon` / `color` 是语义 key，未知值回落 `other` / `gray`，不让脏文件把 UI 搞崩

#### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/categories/templates` | 列出服务器上的模板（文件名 + 模板名 + 条数 + 修改时间） |
| POST | `/api/categories/templates` | 把当前账本分类**保存为服务器模板**：`{name}` → 写 `<模板目录>/<用户id>/<安全文件名>.json` |
| GET | `/api/categories/templates/{file}` | 下载/查看某个模板 JSON |
| POST | `/api/categories/templates/{file}/apply` | 应用到当前账本：`{mode}`，`mode = append|replace` |
| POST | `/api/categories/template` | 兼容「本地上传」：直接传 JSON 内容导入（不落盘） |

- 存储位置默认在**部署目录**：`app.template-dir` 缺省 `${user.dir}/data/templates`，部署时 cwd 是 `deploy/`，所以实际落在 **`deploy/data/templates/{userId}/`**
  - `env.ps1` 可显式设 `TEMPLATE_DIR`；上云时挂一个卷（Docker `-v bookkeep-templates:/app/data/templates`），容器重建不丢
  - 目录由应用启动时自动创建；**`deploy/backup.bat` 要把 `data/templates` 一起打包**，恢复时一起还原
- 文件名安全：只允许 `[a-zA-Z0-9_\-\u4e00-\u9fa5]`，落盘前 URL 编码/白名单化，**禁止 `..` 和路径分隔符**；单用户只能读写自己子目录
- `append`（默认）：同名分类跳过，返回 `{created, skipped:[names]}`，**不报 500**
- `replace`：先删当前账本分类再建——**有流水时拒绝**（400「先清理流水或改用追加」），避免把流水挂空
- 校验：`name` ≤20、`items` 1–100 条、`type ∈ {income,expense}`、分类名 ≤20、单文件 ≤256KB；整份文件先校验再落库（一个事务）

#### 前端

- 分类管理页（§10.4）加一组按钮：**存为模板**（填名字，存到服务器）、**我的模板**（列表 → 应用/下载/删除）、**导入模板**（本地文件 → 预览按支出/收入分组 + 冲突标记 → 追加/覆盖）
- 导入/应用完成后刷新 `categories` 缓存（同 §10.4 的 `reloadCategories()`）
- 新建账本时除了内置「班级 / 通用」，再加「从我的模板」：建完账本直接套用

#### 测试

- 存为模板 → 列表能看到 → 应用到新账本：条数、名称、type、icon/color/sort 完全一致
- 文件名含 `../` 或分隔符：400 / 被清洗，不能写到目录外
- append 到已有分类的账本：同名跳过并在 `skipped` 里返回
- replace 到有流水的账本：400，分类不变
- 非法 JSON / 未知 type / 超 100 条 / 超 256KB：400

#### 不做

- 模板表、模板市场、跨用户共享模板（文件 + 用户子目录已经够用）

### 10.8 AI 功能（拓展，**部署完成后再做**）

定位：AI 只做「输入加速器 + 解释器」，**不做自动记账**。属于加分项，排在 W4 部署与 10.3–10.7 之后。

三条硬约束：

1. **AI 永不直接写库**：只产出草稿/建议，用户确认后走既有 `POST /api/transactions`（校验仍由服务端负责）
2. **fail-open**：超时/额度耗尽/未配置 → 静默退回手工表单，主流程不阻塞
3. **不给模型自由 SQL / 自由工具**：问答类只做受限意图（预定义查询映射到现有统计接口）

实施顺序（每个都可单独验收；先搭边车骨架，再接功能）：

| 顺序 | 功能 | 接口（Java 对外） | 要点 |
|---|---|---|---|
| ⓪ | AI 边车骨架 | `GET /health`（Python 内部）；Java 无对外接口 | FastAPI + LangGraph 空图跑通；`HttpAiClient` 能调通、能超时降级；`DisabledAiClient` 让未配置时整体隐藏 |
| ① | 一句话记账 | `POST /api/ai/parse-transaction {text}` → `{amount,type,categoryId?,transDate,note,confidence}` | 规则优先（金额/日期/收支词/关键词）、模型兜底；结果只回填表单不保存 |
| ② | 月度综述 | `POST /api/ai/monthly-insight {month?}` → `{text}` | **数字全部来自 `StatisticCalculator`**，模型只负责措辞；同账本同月同数据版本缓存；不可用时用模板句兜底 |
| ③ | 智能分类建议 | `POST /api/ai/suggest-category {note,type}` → `{categoryId,categoryName,confidence}` | 只在当前账本该方向的候选分类里选；备注失焦时展示，不自动改动用户选择 |

横切设计（2026-11-07 定稿：**Python LangChain + LangGraph 边车**）：

> 选型决定：用户已学 Python LangChain，明确要求用起来，并直接使用 **LangGraph**。
> 因此 AI 部分不嵌进 JVM，也不引 LangChain4j/Spring AI，而是单独一个 Python 无状态服务。
> 这是**外部能力边车**，不是微服务拆分：它不连数据库、不保存用户数据、不参与业务事务，
> 失败时 Java 侧静默降级，主流程不受影响。

技术栈：

| 层 | 选型 |
|---|---|
| 运行时 | Python 3.12 + FastAPI + uvicorn |
| 编排 | **LangGraph**（`StateGraph` + 条件边 + 重试环） |
| 模型 SDK | LangChain：`langchain-core` + `langchain-openai`（任何 OpenAI 兼容端点；Ollama 走它的 `/v1`） |
| 校验 | Pydantic v2（请求/响应 + 模型输出二次校验） |
| 依赖锁定 | `requirements.txt` 全量固定版本（或 `uv.lock`），禁止部署时 `pip install -U` |
| 测试/评测 | pytest + 30 条 JSON 样例 |

自定义 API（**默认留空，不绑定任何厂商**）：

- 仓库内**不写默认 `base_url`、模型名或 key**：三项都空 = AI 关闭，开箱即用不产生任何外部请求
- 唯一接入方式是「OpenAI 兼容」：用户自己填地址、模型、key，可指向
  智谱（`https://open.bigmodel.cn/api/paas/v4` + `glm-4-flash`）、百炼、硅基流动、DeepSeek、
  OpenRouter、自建 vLLM，或本机 Ollama（`http://127.0.0.1:11434/v1`）
- 模型名是**自由字符串**，不做白名单：换模型只改环境变量，不改代码、不发版

配置项（全部可空）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_BASE_URL` | **空** | OpenAI 兼容地址（含 `/v1` 或厂商等价前缀） |
| `AI_MODEL` | **空** | 模型 ID，例如 `glm-4-flash` |
| `AI_API_KEY` | **空** | 只存在 Python 侧，Java 不持有 |
| `AI_TIMEOUT` | `8` | 秒；Java 与 Python 都按它兜底 |

- 判定规则：**`AI_BASE_URL` / `AI_MODEL` / `AI_API_KEY` 任一为空 → 视为未配置**，
  使用 `DisabledAiClient`，不发起任何外部调用；`/health` 返回 `{"configured": false}`；
  前端整体隐藏 AI 入口，不弹错误
- 配置错误（URL 不合法、401/403）只记 WARN 并按不可用处理，**绝不回落到某个“默认厂商”**
- `.env.example` / `deploy/env.ps1.example` 这几项一律留空，只放注释示例；真实值不进仓库
- 日志与 `/health` 不回显 `AI_API_KEY`、`AI_BASE_URL`（最多显示“已配置/未配置”）

服务形态：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/parse-transaction` | 文本 + 候选分类 → 草稿 |
| POST | `/v1/monthly-insight` | 事实 JSON → 综述文本 |
| POST | `/v1/suggest-category` | 备注 + 类型 + 候选分类 → 分类建议 |
| GET | `/health` | 存活探针，不触发模型调用 |

- 只绑 `127.0.0.1:8000`，**不 publish 端口**；云上只在 compose 内网暴露给 `app`
- 模型 key 只存在 Python 侧（`AI_API_KEY`），Java 侧不持有
- 请求带 `X-AI-Token`（部署时生成的共享串），防止同机其它进程白嫖
- 无状态：不落库、不写文件、不存对话；**LangGraph 不使用 checkpointer**，不需要持久化状态
- 单次请求硬超时 8s（Java 侧兜底 + Python 侧 `asyncio.timeout`），`recursion_limit` 限制图循环次数

LangGraph 具体怎么用（避免“为了用而用”）：

- **parse 图**：`normalize → rule_extract → (金额/分类齐全?)`
  - 是 → `finalize`
  - 否 → `llm_extract → validate → (合法?)`
    - 是 → `finalize`
    - 否 → `repair`（最多 1 次，把校验错误回灌给模型）→ `finalize`
- **insight 图**：`facts → generate → numeric_guard`
  - 数字全部能在事实表里找到 → `finalize`
  - 否则 → `template_fallback`
- 条件边只做「继续 / 重试 / 降级」三种决策；**不做多智能体、不做工具调用、不做 RAG**
  （13 个分类直接进 prompt，上向量库是过度设计）
- LangGraph 在这里解决的是「多步 + 条件 + 重试」，不是包一层单次 LLM 调用——这是面试要讲清楚的点

Java 侧（`bookkeep-service/ai`，只做网关与降级）：

- `AiClient` 接口 + 两个实现：`HttpAiClient`（Spring 6 `RestClient` 调边车）/ `DisabledAiClient`（**base-url/model/key 任一为空**，或 `app.ai.enabled=false`）
- 超时 8s、失败只记日志、返回空结果；前端功能整体隐藏，不弹错误
- 单用户 20 次/天（`SimpleRateLimiter`）、同文本 24h 缓存仍留在 Java 侧（边车不承担业务限流）
- Controller 路径不变：`/api/ai/*`；前端只认 Java，**绝不直连 Python**
- Java 不做任何 AI SDK 依赖；`langchain4j`/`spring-ai` 一个都不加

契约与评测：

- `docs/ai-contract.md` 固化请求/响应 JSON Schema + 错误码；Java DTO 与 Pydantic 模型按同一份写
- Eval 放 Python 侧 `ai-service/evals/booking.json` 30 条 + `pytest`，
  输出金额/类型/分类 Top-1 准确率与 P95 延迟到 `docs/ai.md`
- Java 侧只测「边车不可用」的降级路径（MockMvc + `DisabledAiClient`），不重复跑模型

部署变化（明确接受的成本）：

- 仓库新增 `ai-service/`（FastAPI 源码 + `requirements.txt` + `README.md` + `evals/`）
- 本机：`deploy/start-all.ps1` 增加启停 Python 进程，`stop-all.ps1` 一并清理；日志进 `deploy/logs/ai-service.log`
- 云端：`docker/docker-compose.yml` 增加 `ai` 服务（不 publish 端口，仅 `app` 可访问）
- 备份不受影响（AI 不存数据）；但部署文档必须写清楚 **Python 版本 + 虚拟环境 + 依赖安装**，否则换机器跑不起来
- AI 配置全部可选：`AI_BASE_URL` / `AI_MODEL` / `AI_API_KEY` 留空时边车只提供 `/health`，Java 隐藏入口，不影响记账/统计/公示
- 这是本项目唯一的多语言点，面试按「外部能力边车」解释，不按「微服务」解释

不做：自动记账/自动删除、自由 SQL 问答、预算预测、票据 OCR、语音记账、key 进前端；
也不做多智能体、**工具调用直连数据库**、RAG/向量库、LangGraph checkpointer（无状态是刻意选择）。

最低交付（时间不够时）：⓪ 边车骨架 + ① 一句话记账 + eval + `docs/ai.md`，足够支撑 3 分钟面试讲解。

面试叙事：① AI 只生成草稿，写库走确定性接口；② 规则优先 + 8s 超时 + fail-open，说明考虑了成本与可用性；
③ 有 30 条固定样例的准确率评测，不靠「感觉效果不错」；
④ 为什么单独 Python 边车：AI 是唯一需要 Python 生态的能力，做成无状态边车比塞进 JVM 更清晰；
LangGraph 只用来表达「校验失败 → 重试 → 降级」的条件状态机，不为了用而用。

### 10.9 票据（收据/小票）上传与导出 ✅ 已完成（2026-11-10）

> 落地口径：见 `AGENTS.md` 的 Receipts 一条。要点补充——
> 单笔 9 张 / 单张 5MB / 账号 500MB；魔数识别（SVG 拒收）；`stored_path` 为唯一事实来源；
> 两种 zip（账本级带区间、单笔）共用目录规则与 `manifest.csv`；导出时**缺失文件跳过并在 manifest 标注**，
> 不产出 0 字节条目；`ReceiptOrphanJob` 每天 4:20 清理「超过 1 小时且库里没有记录」的孤儿文件
> （上传是「先落盘、后插库」，minAge 就是为了不误删正在上传的那张）。
> 前端看图必须走 blob（登录态接口不能 `<img src>`），所以 nginx 的 CSP `img-src` 必须含 `blob:`。

记一笔时可以给这笔账挂多张票据照片，支持打包导出，**也支持在公示页免登录查看**。**不做 OCR**（§10.8 已明确排除），只做「存图 + 看 + 导出」。

#### 数据与存储

- 新表 `transaction_receipt`：`id / transaction_id / title(用户命名，≤30 字) / original_name(上传时文件名，仅展示) /
  stored_path(相对 receipt-dir 的路径) / content_type / size_bytes / created_at`
  - **不设逻辑删除字段**：票据随流水生命周期走
  - `title` 可改，默认取上传文件名的去扩展名部分（如「超市小票」）
- 存储按**账本名 / 月份**分层，便于人工浏览与拷走：
  ```
  deploy/data/receipts/
    └── 计科2301班费_12/          ← 安全化后的账本名 + bookId（保证改名/重名也不串）
        ├── 2026-11/
        │   ├── 3f2a9c8e….jpg
        │   └── 9b1c44d0….png
        └── 2026-12/
  ```
  - 「月份」取流水的 **`trans_date`**（业务日期），不是上传时间——补录上月票据时应该归到上月
  - 目录名 = 账本名去掉 `\/:*?"<>|` 与控制字符、去首尾点/空格、截断 30 字，再拼 `_{bookId}`；
    纯非法字符时回落 `book_{bookId}`
  - **账本改名不搬目录**：`stored_path` 才是唯一事实来源，目录名只是便于人看；搬目录会让大文件重命名失败时数据不一致
  - 流水改日期/切换账本也不搬文件：老图留在原目录，新图进当前目录（同样以 DB 路径为准）
- 文件落盘名一律服务端 UUID，**用户命名只进 `title`，不参与路径**（防路径穿越）
- `env.ps1` 可设 `RECEIPT_DIR`；云端挂卷（同 §10.7 的模板目录）；`backup.bat` 必须把 `data/` 一起打包

#### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/transactions/{id}/receipts` | multipart（单文件 + `title?`）；返回 `{id, title, size}`。多选时前端逐张调用 |
| GET | `/api/transactions/{id}/receipts` | 该笔的票据列表（id/title/原始名/大小/时间，不含内容） |
| GET | `/api/transactions/{id}/receipts/{receiptId}` | 下载/预览单张（inline，需登录 + 账本归属校验） |
| PUT | `/api/transactions/{id}/receipts/{receiptId}` | 只改 `title`（≤30 字，空则回落原始名） |
| DELETE | `/api/transactions/{id}/receipts/{receiptId}` | 删除单张（同时删文件，失败只记日志不阻断） |
| GET | `/api/transactions/receipts/export?start&end` | 按区间导出 zip（含 `manifest.csv`） |

导出规则（与存储保持同一套命名/分层，导出的 zip 解开就能对上目录）：

- **zip 内目录 = 账本名 / 月份**，与磁盘一致：
  ```
  {账本名}_{bookId}.zip
    ├── 计科2301班费_12/
    │   ├── 2026-11/
    │   │   ├── 2026-11-05_超市小票.jpg
    │   │   └── 2026-11-06_班服收据.png
    │   └── 2026-12/…
    └── manifest.csv
  ```
- 文件名 = `{日期}_{票据标题}.{ext}`（标题同样去非法字符、截断；同目录重名自动 `_2`、`_3`）
- 单笔导出：zip 名 `{账本名}_{日期}_{分类}_{金额}.zip`，内部同结构（单月一个目录），附 `manifest.csv`
- `manifest.csv` 列：日期 / 类型 / 分类 / 金额 / 备注 / 票据标题 / 相对路径
- 区间导出只导**当前账本**（和 CSV 导出一致）；要跨账本分别导两次
- 上限：一次 ≤200 张、≤100MB；超了返回 400 并提示缩小范围（避免把试用机内存打满）
- zip 用 `java.util.zip` 流式写，不把全部图片读进内存

#### 校验与安全（这类功能最容易出事）

- 类型只收 `jpg/jpeg/png/webp`，**按魔数判断**而不是看扩展名；**不收 SVG**（SVG 可带脚本，等于存储型 XSS）
- 单张 ≤5MB、单笔 ≤9 张、单用户总量配额（默认 500MB，超了 400 并提示清理）
- 下载响应带 `Content-Type`（服务端识别）+ `Content-Disposition: inline`（文件名 URL 编码）+ `nosniff`
- **登录态接口不放匿名**：`/api/transactions/**` 一律要 token + 账本归属校验；公示页的票据走**独立只读路径**
  （下面「公示页票据」），不复用登录态接口，也不把票据混进需要 token 的导出
- 归档账本（§10.5）禁止上传/删除票据，但允许导出（只读）

#### 生命周期

- 流水软删除：票据保留（回收站语义一致）
- 账本到期物理删除（`BookPurgeJob`）与**注销账户**（§5.3 立即物理删除）：必须**同时删文件目录**，
  顺序「先删库记录 → 再删目录」，目录删失败只记日志（不能因为文件删不掉就回滚账号注销）
- 孤儿清理：启动/每日任务扫描 `data/receipts` 下没有对应 `transaction_id` 的目录（可选，防止历史脏数据）

#### 前端

- 「记一笔」弹窗：新增「票据」区域，`<input type="file" accept="image/*" multiple>`（手机端可直接拍照），
  选完先本地预览，每张可改标题（默认文件名去扩展名），可删除；保存流水成功后再上传（先有 `transaction_id` 才能挂图）
- 流水列表行：显示票据数量徽标；点开详情弹窗看缩略图、改标题、单张下载
- 导出：列表页「导出票据」按当前筛选区间打包（与 CSV 导出并列），zip 内按 `账本名/月份` 分层，与磁盘一致
- 图片回显用后端**登录态**接口 + `fetch` 转 blob，**不要**用 `<img src>` 直链——登录态接口不能进匿名白名单；公示页才用直链（见下）

#### 测试

- 上传多图 + 自定义标题 → 列表 `title` 正确；空标题回落原始文件名
- 非图片/超 5MB/超 9 张 → 400；改标题超 30 字 → 400
- 目录规则：上传落在 `{安全化账本名}_{bookId}/{yyyy-MM}/`，月份取 `trans_date`；账本改名后新图进新目录、老图仍可读
- 越权：A 访问 B 的票据 → 404
- 导出 zip：内部目录为 `账本名/月份/`、文件名 `{日期}_{标题}`、`manifest.csv` 行数与相对路径正确、重名自动加后缀
- 注销账户/账本物理删除后：库里记录与磁盘目录都消失
- 归档账本：上传 400、导出 200

#### 公示页票据（只读、免登录）

公示页（§5.9）除了金额明细，也能看这笔账的票据照片。**只读、免登录、不能下载原图以外的操作**。

接口（都挂在已有的 `/api/public/report/**` 白名单下，`SecurityConfig` 不用再加放行规则）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/public/report/{shareToken}` | 响应里每笔流水增加 `receiptCount` + `receipts:[{id, title}]`（无文件内容） |
| GET | `/api/public/report/{shareToken}/receipts/{receiptId}` | 返回图片字节（inline），供公示页缩略图/大图 |

访问校验（四条缺一不可，任一不满足都返回 404，不能回 403/400 暴露「存在但无权」）：

1. `share_token = ?` 且 `share_token_expires_at > now()`（与 §5.9 同一套判断，同一个时钟）
2. 账本未删除（`book.deleted = 0`）
3. `receiptId` 所属流水的 `book_id` = 该 token 的账本（**不能只按 receiptId 查**）
4. 该流水 `deleted = 0` 且票据记录存在（流水进回收站后，公示页也看不到它的票据）

安全约束：

- **图片仍按魔数识别**，只回 `image/jpeg`、`image/png`、`image/webp`；`Content-Disposition: inline` + `X-Content-Type-Options: nosniff`
- 响应不返回原始文件名、磁盘路径、`transaction_id`、`userId`，只有字节和 `Content-Type`
- 只读：**没有**匿名上传/删除/改名/zip 导出的入口；区间导出 `receipts/export` 仍要登录
- 不做目录列举：`receiptId` 递增可被猜，但 1–4 校验不过一律 404，猜中也没用
- 加 `Cache-Control: private, max-age=300`（图片不大、链接 3 天过期，短缓存够用；不设 `public`，避免中间缓存留存）
- 匿名入口只针对图片，**不要**给公示页加 `/api/public/**` 的更宽匹配
- 单 IP 粗限流可选：图片接口复用 Nginx `limit_req` 或 `SimpleRateLimiter`，防止有人拿一个有效链接刷满带宽（试用机 1M 带宽很容易被拖死）

前端 `PublicReportView.vue`：

- 明细行右侧显示票据数量徽标，点开抽屉/图集看缩略图；大图点开再看原图
- 图片地址直接用 `/api/public/report/{token}/receipts/{receiptId}`（匿名，可用 `<img src>`），不需要 `fetch` 转 blob——这与登录侧相反
- 票据标题（`title`）展示给访客；**不显示**原始文件名
- 没有票据时整块隐藏，不留「暂无票据」占位，避免公示页显得残缺

测试：

- 正常链接：明细里 `receiptCount`/`receipts` 与上传一致；图片接口 200 且 `Content-Type` 正确
- 过期链接 / 从未分享 / 账本已删：列表与图片接口都 404
- 跨账本：A 的 token 请求 B 的 `receiptId` → 404
- 流水软删除后：公示页不再出现该笔，直接请求其票据 → 404
- 非图片内容（改扩展名上传的 txt）→ 该票据根本不会入库；若库里被手工塞了脏记录，图片接口 404/415，不能回显任意文件
- `PublicReportApiTest` 继续**不带** `Authorization`，新增用例钉住上面 4 条

#### 不做

- OCR 识别金额（§10.8 已排除）、图片压缩服务端处理（前端压缩可选）、OSS 对象存储、
  **匿名 zip 导出 / 匿名改标题 / 匿名删除**（公示页只给逐张看图，见上）

#### 与已有功能的关系

- §10.3 流水导入导出（CSV/xlsx）**不携带图片**：导入只处理金额与分类，票据需要单独补
- §10.5 归档账本：票据只读
- §5.9 公示页：明细里带票据数量与标题，图片走独立的匿名只读路径（见「公示页票据」）
- §10.7 模板目录与 §10.9 票据目录都属于「部署数据」，备份要一起做

建议排期：`10.9 票据` 放在 **10.3–10.7 完成、部署稳定之后、10.8 AI 之前**——它不依赖模型、用户价值直接，且能提前暴露「文件存储 + 备份」这类真正容易翻车的问题。上传/导出与「公示页票据」**同批交付**，别拆成两次（同一张表、同一套校验，拆开要回归两遍）。
