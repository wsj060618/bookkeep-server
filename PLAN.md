# 记账网站后端开发计划书（bookkeep-server）

## 1. 项目信息

- 后端位置：`D:\coding\bookkeep-server`（本人开发）
- 前端位置：`D:\coding\bookkeep-web`（后续由 AI 协助开发，严格按本计划书接口契约）
- 技术栈：
  - Spring Boot 3.3.x + JDK 17 + Maven
  - MyBatis-Plus（`mybatis-plus-spring-boot3-starter`）+ MySQL 8
  - Lombok + spring-boot-starter-validation
  - Spring Security + JWT（jjwt 0.12.x）
- 工程结构：**Maven 父子工程（多模块）**，父工程 `bookkeep-server` 下拆三个子模块

## 2. 核心设计

### 2.1 多账本
- 一本账本 = 一套独立的记账数据（分类 / 流水 / 预算）
- 账本带 `type`（`personal` 个人 / `merchant` 商家），统计口径按当前账本类型切换
- 用户可自由新建 / 重命名 / 删除 / 切换多本账本

### 2.2 注册建账本
- 注册时选择类型 + 账本名，自动创建第一本账本并预置对应分类模板
- `user.current_book_id` 指向当前账本，存后端

### 2.3 数据隔离
- 分类 / 流水 / 预算 / 统计 / AI 分析全部按「当前账本」过滤
- 前端无需传 book_id，后端从当前用户取 current_book_id
- 防越权：所有查询强制带 book 归属校验

### 2.4 AI 分析
- 本地规则引擎生成结构化分析报告
- `AiAnalysisService` 接口抽象，预留大模型 API 实现（后续 `@ConditionalOnProperty` 切换）

### 2.5 文件存储（头像）
- 头像只在 `user.avatar` 存 **URL 字符串**（`VARCHAR(500)`），图片本体上传到**阿里云 OSS**
- `FileService` 接口抽象，`AliyunOssFileServiceImpl` 为 OSS 实现，预留切换（同 §2.4 用 `@ConditionalOnProperty`）
- OSS 配置（endpoint / bucket / accessKeyId / accessKeySecret / urlPrefix）走**环境变量**，禁止硬编码进仓库
- `FileService` 入参用 `InputStream + 原始文件名`，**不引 `MultipartFile`**，避免 service 模块依赖 spring-web（转换在 controller 层做）

## 3. 数据库设计

建库：`CREATE DATABASE bookkeep DEFAULT CHARACTER SET utf8mb4;`

```sql
CREATE TABLE `user` (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  username       VARCHAR(50)  NOT NULL UNIQUE,
  password       VARCHAR(100) NOT NULL,          -- BCrypt
  nickname       VARCHAR(50),
  email          VARCHAR(100),
  avatar         VARCHAR(500),                   -- 头像 URL（阿里云 OSS）
  current_book_id BIGINT,                        -- 当前打开的记账本
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted        TINYINT DEFAULT 0
);

CREATE TABLE book (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  name        VARCHAR(50) NOT NULL,
  type        VARCHAR(10) NOT NULL,              -- 'personal' / 'merchant'
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT DEFAULT 0
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

### 分类模板（注册 / 新建账本时按 type 预置）

| type | 支出分类 | 收入分类 |
|---|---|---|
| personal | 餐饮、交通、购物、住房、娱乐、医疗、学习、其他 | 工资、奖金、理财、其他 |
| merchant | 采购、租金、员工工资、水电、物流、税费、设备、营销、其他 | 销售收入、服务收入、其他 |

## 4. 工程与包结构（Maven 多模块）

依赖方向：`web → service → common`（单向，不反向依赖）

```
D:\coding\bookkeep-server\                父工程（packaging=pom，只管模块聚合与依赖管理）
├── pom.xml                               spring-boot-starter-parent + 统一依赖版本管理
├── bookkeep-common\                      公共模块：纯工具 + 统一返回，不依赖业务
│   └── com.bookkeep.common
│       ├── Result、ResultCode、PageResult
│       ├── BusinessException
│       ├── JwtUtil、SecurityUtil
│       └── constant...
├── bookkeep-service\                     业务模块：实体 + 数据访问 + 业务逻辑
│   └── com.bookkeep.service
│       ├── entity       User、Book、Category、Transaction、Budget
│       ├── mapper       UserMapper、BookMapper、CategoryMapper、TransactionMapper、BudgetMapper、StatisticMapper
│       ├── dto
│       │   ├── req      RegisterReq、LoginReq、BookReq、CategoryReq、TransactionReq、BudgetReq、UserUpdateReq
│       │   └── resp     LoginResp、BookResp、TransactionResp、SummaryResp、CategoryStatResp、AnalysisReportResp ...
│       ├── impl 与接口  AuthService、BookService、CategoryService、TransactionService、BudgetService、StatisticService
│       ├── file
│       │   ├── FileService                  （接口：上传返回 URL）
│       │   ├── AliyunOssFileServiceImpl     （阿里云 OSS 实现）
│       │   └── OssProperties                （@ConfigurationProperties(prefix = "oss")）
│       └── analysis
│           ├── AiAnalysisService        （接口）
│           ├── RuleAnalysisServiceImpl  （本地规则实现）
│           └── AnalysisContext / AnalysisReport
└── bookkeep-web\                         启动模块：启动类 + 控制器 + 配置
    └── com.bookkeep.web
        ├── BookkeepApplication            主启动类（@SpringBootApplication + @MapperScan 扫 service 模块 mapper）
        ├── controller    AuthController、BookController、UserController、CategoryController、
        │                 TransactionController、StatisticController、BudgetController、AnalysisController
        ├── config        SecurityConfig、MybatisPlusConfig、JacksonConfig、CorsConfig
        ├── handler       GlobalExceptionHandler
        └── resources/application.yml
```

> 测试按标准 Maven 约定放**每个模块自己的 `src/test/java`**（不设独立 test 模块）：
> - `common`：JwtUtil / Result / ResultCode 纯单元测试
> - `service`：service 层 Mockito 单测、mapper 测试
> - `web`：`@SpringBootTest` + MockMvc 接口集成测试（完整上下文在此模块，DB 需可用）

### 各模块依赖清单

| 模块 | 依赖 | 说明 |
|---|---|---|
| `bookkeep-common` | lombok、jjwt、spring-security-core | 轻依赖，JwtUtil/SecurityUtil 用 |
| `bookkeep-service` | spring-boot-starter、mybatis-plus-spring-boot3-starter、mysql-connector-j、spring-boot-starter-validation、bookkeep-common、aliyun-sdk-oss（+ JDK17 所需 jaxb-api/jaxb-runtime/activation） | 实体/mapper/service/file |
| `bookkeep-web` | spring-boot-starter-web、spring-boot-starter-security、spring-boot-starter-aop、bookkeep-service | 启动/控制器/配置 |
| 各模块 | spring-boot-starter-test(test) | 每模块自带单测；web 额外 spring-security-test |

### 日志功能
- `logback-spring.xml`（web 模块 resources）：控制台 + 滚动文件（`logs/bookkeep-server.log` 按天滚动，保留 30 天），ERROR 单独落 `-error.log`；日志带 `[环境]` 标识
- 环境级别：默认/非 prod → `com.bookkeep` DEBUG、mapper DEBUG（看 SQL）；`prod` 环境 → 业务 INFO、mapper INFO。启动时 `--spring.profiles.active=prod` 切换
- MyBatis-Plus SQL 日志改走 SLF4J（`log-impl: Slf4jImpl`），mapper 包设为 DEBUG 级别
- `LogAspect`（web 模块 aspect 包）：AOP 自动记录所有 controller 调用——方法/参数/耗时，超 1s 标 WARN，异常标 ERROR；自动脱敏 password/token 字段
- `StartupInfoRunner`（web 模块 config 包）：启动后打印应用名称/版本/端口/环境横幅（版本来自 spring-boot `build-info` 插件）
- 业务代码里直接用 Lombok `@Slf4j` 打日志

### 多模块注意点
- 三个子模块包名统一在 `com.bookkeep` 下，但启动类在 `com.bookkeep.web`，默认组件扫描只覆盖 `com.bookkeep.web`，**必须**给 `BookkeepApplication` 加 `scanBasePackages = "com.bookkeep"`，否则 common/service 的 Bean 扫不到
- `@MapperScan("com.bookkeep.service.mapper")` 加在主启动类或 MybatisPlusConfig
- `application.yml` 只在 web 模块，服务启动入口是 `bookkeep-web`
- 测试按标准约定放各模块 `src/test`，`@SpringBootTest` 集成测试放 web 模块
- 父工程 `dependencyManagement` 统一版本，子模块不写版本号

## 5. 接口契约

- 统一前缀 `/api`，认证用 `Authorization: Bearer <token>`
- 统一返回 `{ code, message, data }`，`code=200` 成功
- 常用 code：200 成功 / 400 参数错误 / 401 未登录或 token 失效 / 403 无权 / 500 服务器异常
- 出错时 **HTTP 状态码与 `code` 一致**（`ResponseEntity.status(code)`），成功为 200

### 5.1 认证

| 方法 | 路径 | 说明 | 请求体 / 参数 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册并创建第一本账本 | `{username, password, nickname?, email?, bookName, bookType}` → 用户信息 |
| POST | `/api/auth/login` | 登录 | `{username, password}` → `{token, user:{id, username, nickname, email, currentBookId, books:[...]}}` |
| GET | `/api/auth/me` | 当前用户信息（含 currentBookId） | - |

### 5.2 账本

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/books` | 账本列表，含每本 `{id, name, type, current:bool}` |
| POST | `/api/books` | 新建 `{name, type}`，预置分类模板 |
| PUT | `/api/books/{id}` | 重命名 `{name}` |
| DELETE | `/api/books/{id}` | 删除账本及其数据；若为当前账本，current_book_id 指向剩余第一本（无则置 null） |
| PUT | `/api/books/{id}/current` | 切换当前账本 |

### 5.3 用户

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT | `/api/users/me` | 改昵称 / 邮箱 / 头像 `{nickname?, email?, avatar?}` |
| POST | `/api/users/me/avatar` | 上传头像（multipart 字段 `file`）→ 传 OSS 后返回 `{avatar}` 并更新用户 |

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
| POST | `/api/transactions` | 新增 `{categoryId, type, amount, transDate, note?}` |
| PUT | `/api/transactions/{id}` | 修改（仅自己账本） |
| DELETE | `/api/transactions/{id}` | 删除（仅自己账本） |

返回 DTO 含 `categoryName`、`categoryIcon`、`categoryColor`。

### 5.6 统计（按当前账本及其类型）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/statistics/summary?month=2026-08` | 个人：`{income, expense, balance}`；商家：`{income, expense, profit, profitRate}` |
| GET | `/api/statistics/category?month=&type=expense` | 分类占比 `[{categoryName, amount, percent}]` |
| GET | `/api/statistics/trend?months=6` | 近 N 月收支趋势 `[{month, income, expense}]`（补零） |
| GET | `/api/statistics/daily?days=30` | 近 N 天每日支出 `[{date, amount}]`（补零） |
| GET | `/api/statistics/profit?months=6` | 商家利润趋势 `[{month, income, cost, profit}]` |
| GET | `/api/statistics/cost-structure?month=` | 商家成本分类占比（即支出的分类占比） |

### 5.7 预算（按当前账本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/budgets?month=2026-08` | 当月预算列表，含已用 / 剩余 / 进度 |
| POST | `/api/budgets` | 新增 `{categoryId, month, amount}`（同月同分类唯一键兜底） |
| PUT | `/api/budgets/{id}` | 修改 |
| DELETE | `/api/budgets/{id}` | 删除 |

### 5.8 AI 分析（按当前账本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/analysis/report?month=2026-08` | 结构化分析报告 |

报告规则（每条带 `level: normal/warning/danger`）：
1. 本月总支出 vs 上月 → 环比百分比，超 20% 预警
2. 逐分类对比预算，超支标红预警
3. 支出 Top 3 分类及占比，占比过高建议
4. 日均支出 vs 上月日均，趋势判断
5. 结余情况，支出 > 收入标 danger
6. 商家额外：利润环比下滑 / 成本超支预警

## 6. 开发顺序

- **P1** 骨架 + 统一返回/异常 + JWT 认证 + 注册建账本（模板分类批量插入）
- **P2** 分类 + 流水 CRUD（book 隔离 + 分页筛选）
- **P3** 统计接口（聚合 SQL，补零处理）
- **P4** 账本管理（增删改 + 切换当前）+ 预算
- **P5** AI 分析（规则引擎 + 接口抽象）
- **P5.5** 头像上传（`FileService` 接口 + 阿里云 OSS 实现 + `POST /api/users/me/avatar`）
- **P6** 收尾：防越权逐接口验证、删除联动、参数校验

### P1 细化（每天约 1 小时，按真实开发顺序）

- **第 0 天 ✅ 已完成**：建库建表 + `application.yml`（UTF-8 + 环境变量 `${DB_USER}/${DB_PWD}`）+ `ResultCode`/`Result`/`PageResult`/`BusinessException`/`JwtUtil` + `BookkeepApplication(scanBasePackages)` + `DataSourceConnectionTest`
- **第 1 天 ✅ 已完成**：`User` 实体 + `UserMapper` + `UserMapperTest`（持久层 + 逻辑删除验证，测试通过）
- **第 2 天**：`Book`/`Category` 实体 + `BookMapper`/`CategoryMapper` + `BookType`/`CategoryType`/`CategoryTemplate`
- **第 3 天**：最小 `SecurityConfig`（先 `permitAll`，提供 `BCryptPasswordEncoder`）+ `GlobalExceptionHandler`
- **第 4 天**：注册接口（`RegisterReq`/`UserResp`/`BookResp` + `AuthService.register` + `AuthController.register`）
- **第 5 天**：登录发 token（`LoginReq`/`LoginResp` + `AuthService.login` + `AuthController.login`）
- **第 6 天**：`SecurityUtil` + `JwtAuthenticationFilter` + 收紧 `SecurityConfig`（放行 auth，其余需认证）+ `/api/auth/me`
- **第 7 天**：401/403 JSON 处理器 + P1 全量验收（PLAN §7）
- **缓冲（可选）**：`JacksonConfig`（LocalDate/LocalDateTime 格式），等响应含日期字段再加

> 说明：`SecurityConfig` 会在第 3 天（临时放行）和第 6 天（收紧）各改一次，以便第 4–5 天的接口在认证做完前就能调通。

## 7. 自查清单

- **P1**：注册（带类型）→ 登录 → 带 token 访问 `/api/auth/me` 成功；无 token 返回 401 JSON；错参 400
- **P2**：Postman 建分类、记流水、按日期/类型/关键字分页筛选正常；A 用户看不到 B 用户数据
- **P3**：手造一个月数据，summary/category/trend/daily 与手算一致
- **P4**：新建 / 切换 / 删除账本联动正确（删除当前账本自动转移）；预算进度正确
- **P5**：report 输出结构化规则分析，商家账本含利润分析
- **P5.5**：上传头像 → `user.avatar` 存 OSS URL，`/api/auth/me` 能返回该 URL；超大/非法格式文件返回 400
- **P6**：登录 A 账号不能查/改/删 B 账号任何数据（逐接口验证）

## 8. 避坑清单

1. SpringBoot3 必须用 `mybatis-plus-spring-boot3-starter`（不是 `mybatis-plus-boot-starter`）
2. jjwt 0.12.x API 与旧版不同：`Jwts.builder().signWith(key)` 需传 `SecretKey` 对象（`Keys.hmacShaKeyFor(secretBytes)`），不是字符串
3. 金额用 `BigDecimal`，数据库 `DECIMAL(12,2)`，不用 double
4. JWT 过滤器注册在 `UsernamePasswordAuthenticationFilter` 之前；解析失败直接写 401 JSON 返回
5. `SecurityUtil.getUserId()` 从 SecurityContext 取当前用户，Controller 不从 token 现拆
6. 逻辑删除：MyBatis-Plus 全局配置默认拼 `deleted=0`，但**手写 `@Select` 的 SQL 不会自动拼**，统计 SQL 要自己加
7. 日期统一 `yyyy-MM-dd` 传参，统计按 `DATE_FORMAT(trans_date,'%Y-%m')=#{month}` 比较，避免时区坑
8. 跨域：开发用 Vite 代理（`/api` → `http://localhost:8080`）、生产用 nginx 反向代理，前后端同源，**无需 `CorsConfig`**；Jackson 日期格式按需配 `JacksonConfig`（`spring.jackson.date-format` 只对 `java.util.Date` 生效，LocalDate/LocalDateTime 需单独配）
9. 删除账本 / 分类注意数据联动（逻辑删除其下流水）
10. 统计接口结果补零，前端图表才不断层
11. 阿里云 OSS SDK 在 JDK17 下需额外引 `jaxb-api` / `jaxb-runtime` / `activation`，否则运行时抛 `ClassNotFoundException: javax.xml.bind.JAXBException`
12. OSS 的 AK/SK、bucket、endpoint 等敏感配置走环境变量（与数据库密码同理），禁止写进 `application.yml` 提交
13. 头像上传需限制文件类型（jpg/png/webp）与大小（如 ≤2MB），并用唯一对象名（如 `avatar/{userId}/{UUID}.{ext}`）避免覆盖
14. `LogAspect` 会对参数做 JSON 序列化，`MultipartFile` 参数会序列化失败或产生巨大日志，需在上传接口的日志处理中对 `MultipartFile` 跳过序列化
