# bookkeep
![Java 17](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.5-1F6FEB)
![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.9-C71A36?logo=apachemaven&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)
![Nginx](https://img.shields.io/badge/Nginx-1.27-009639?logo=nginx&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

前端仓库: [bookkeep-web](https://github.com/wsj060618/bookkeep-web)
## 功能

- **账号**：邮箱登录（用户名只是显示名）、注册即建账本、找回密码走邮件一次性 token
- **账本**：多本、随时切换、可设学期截止日期、学期结束归档成只读、删除进 15 天回收站
- **分类**：账号级分类模板，改模板会同步到用过它的账本（有流水的分类不允许删），模板可导出/导入 JSON
- **流水**：增删改查、按日期/类型/分类/备注筛选、逐行结余（不做余额校验，账面可以为负）
- **票据**：每笔最多 9 张、单张 10MB、图片落盘不入库，支持 zip 导入导出
- **统计**：按天区间汇总、分类占比、收支对比
- **导入导出**：CSV、带图表的 Excel、可再导入的账本 zip（流水 + 图片 + 分类模板）
- **公示**：免登录只读页面，一条链接可带多本账本，3 天有效，可看票据
- **安全**：登录限流、改密后旧 token 立即失效、prod 启动自检（弱密钥拒绝启动）

## 技术栈

- **后端**：Spring Boot 3.5 / JDK 17 / MyBatis-Plus 3.5 / MySQL 8.4 / JJWT 0.12 / Apache POI（Excel）/ Caffeine（登录限流）
- **前端**：Vue 3 + TypeScript / Vite / Element Plus / Pinia / vue-router / ECharts
- **部署**：Nginx（静态站点 + `/api` 反代）/ Docker Compose
## 项目结构

后端是一个 Maven 多模块工程，依赖方向单向：`web → service → model`，`service → common`。

```
bookkeep-server/
├── pom.xml                     父 pom（dependencyManagement，本身不打 jar）
│
├── bookkeep-common/            没有任何业务依赖的公共壳
│   └── com/bookkeep/common/
│       ├── result/             Result / ResultCode / PageResult（统一响应信封）
│       ├── exception/          BusinessException（业务错误）
│       └── util/               JwtUtil、SecurityUtil（取当前用户 id）
│
├── bookkeep-model/             只有数据形状，不依赖任何模块
│   └── com/bookkeep/model/
│       ├── entity/             User / Book / Category / Transaction / …
│       ├── dto/req/            请求体（带校验注解）
│       ├── dto/resp/           响应体
│       └── validation/         密码、金额、用户名的唯一规则定义
│
├── bookkeep-service/           业务实现
│   ├── com/bookkeep/service/   接口（Auth / Book / Category / Transaction / …）
│   │   ├── impl/               实现
│   │   ├── constant/           CategoryPresets（内置 11 条班级分类）
│   │   ├── mapper/             MyBatis-Plus Mapper（手写 SQL 在 resources/mapper）
│   │   ├── export/             CSV / Excel / zip 导出
│   │   ├── imports/            CSV / 账本 zip 导入
│   │   ├── mail/               找回密码发信
│   │   └── support/            统计口径、限流、票据落盘、定时清理等
│   └── resources/mapper/       TransactionMapper.xml（窗口函数、动态条件）
│
├── bookkeep-web/               唯一可运行的模块
│   ├── com/bookkeep/web/
│   │   ├── BookkeepApplication.java
│   │   ├── controller/         9 个 REST 控制器（Auth / Book / Category / CategoryTemplate / Transaction / Receipt / Statistic / Public / User）
│   │   ├── config/             安全、Jackson、定时任务、启动自检
│   │   ├── filter/             JWT 认证过滤器
│   │   ├── handler/            全局异常 + 401/403 JSON
│   │   └── aspect/             统一请求日志（密码脱敏）
│   ├── resources/              application.yml / application-prod.yml / logback
│   └── src/test/               集成测试（MockMvc + 真实 MySQL）
│
├── sql/                        schema.sql（全量）+ migration_*.sql（增量）
├── docker/                     Dockerfile / compose / nginx 配置
├── docs/                       部署文档、接口文档、安全审计
├── http/                       IDEA HTTP Client 手工验收脚本
└── deploy/                     本机 Windows 部署脚本（gitignored）
```

前端是独立仓库（`D:\coding\bookkeep-web`）：

```
bookkeep-web/
├── index.html
├── vite.config.ts
└── src/
    ├── api/            按后端模块拆的请求封装（唯一出网口）
    ├── components/     流水行、票据图集、分类表单、图表
    ├── composables/    跨页面共享（当前账本的分类缓存、移动端判断）
    ├── layouts/        AppLayout：侧边栏 + 记一笔弹窗 + 分享弹窗
    ├── router/         路由与登录守卫
    ├── stores/         Pinia（用户、账本列表、token）
    ├── types/          api.ts：与后端 DTO 一一对应的类型
    ├── utils/          校验规则、图标/颜色映射、金额格式化、分页条数、省略号判断
    └── views/          10 个页面（登录/注册/流水/统计/账本/分类/设置/公示…）
```

## 本地运行

需要 JDK 17、Maven、MySQL 8.4（库名 `bookkeep`，utf8mb4）。

```powershell
# 建库建表（首次）
mysql -uroot -p --default-character-set=utf8mb4 -e "source sql/schema.sql"

# 配好数据库账号后运行
$env:DB_USER="root"; $env:DB_PWD="你的密码"
mvn -pl bookkeep-web spring-boot:run
```

前端（另一个仓库）：

```powershell
cd D:\coding\bookkeep-web
npm install
npm run dev        # http://localhost:5173，/api 代理到 8080
```

已存在的库不跑 `schema.sql`，按需执行 `sql/migration_*.sql`；**执行 SQL 必须带 `--default-character-set=utf8mb4`**，否则 Windows 控制台会把中文注释写坏。

## 测试

```powershell
# 后端（必须 clean：增量编译会掩盖符号错误）
$env:DB_USER="root"; $env:DB_PWD="你的密码"
mvn -pl bookkeep-web -am clean test

# 前端
cd D:\coding\bookkeep-web
npm run test
npm run build
```

后端测试是真的连 MySQL（`@Transactional` 自动回滚），所以本机要有库。

## 约定速览

- 响应统一 `{code, message, data}`；业务错误一律 HTTP 200 + `body.code`，只有认证/授权用真实 401/403
- 金额是 `DECIMAL(12,2)`，出口一律两位小数的**字符串**
- 前端从不传 `bookId`，后端从 `user.current_book_id` 推导；写操作走 `writableBookId()`（归档账本只读）
- 手写 SQL 必须自己加 `deleted = 0`（逻辑删除只对 MyBatis-Plus 生成的 SQL 生效）

更细的规则与踩坑记录见 [AGENTS.md](AGENTS.md) 和 [PLAN.md](PLAN.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/部署文档.md](docs/部署文档.md) | 云端部署步骤（Docker Compose） |
| [docs/接口文档.md](docs/接口文档.md) | 接口契约（请求/响应/错误码） |
| [docs/security.md](docs/security.md) | 安全审计与加固记录 |
| [PLAN.md](PLAN.md) | 设计文档：需求、数据模型、踩坑清单、已知缺口 |
| [AGENTS.md](AGENTS.md) | 维护约定：模块边界、当前状态、测试与坑 |

## License

MIT © 2026 wsj060618 — 见 [LICENSE](LICENSE)。