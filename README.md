# bookkeep

班级班费记账系统：Spring Boot 3.5 + Vue 3 + MySQL 8。四个模块的 Maven 后端 + 独立的前端仓库。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/部署文档.md](docs/部署文档.md) | 云端部署（Docker Compose）、备份恢复、连库、排错 |
| [docs/接口文档.md](docs/接口文档.md) | 前端对接用的接口说明（请求/响应/错误码） |
| [docs/security.md](docs/security.md) | 安全审计与加固记录 |
| [PLAN.md](PLAN.md) | 设计文档：需求、接口契约、踩坑清单、已知缺口 |
| [AGENTS.md](AGENTS.md) | 给维护者/agent 的约定：当前状态、模块边界、测试与坑 |

## 目录

```
bookkeep-common   Result / 异常 / JWT / SecurityUtil
bookkeep-model    实体与 DTO
bookkeep-service  mapper + 业务实现（统计口径、导出、导入、票据）
bookkeep-web      启动类 + controller + 安全配置 + 集成测试
sql/              schema.sql（全量）与 migration_*.sql（一次性变更）
docker/           Dockerfile、compose、nginx 配置
http/             IDEA HTTP Client 手工验收脚本
deploy/           本机 Windows 部署脚本（gitignored，见 deploy/README.md）
```

## 本地运行

需要 JDK 17 + Maven + MySQL 8.4（库名 `bookkeep`，utf8mb4）。

```powershell
$env:DB_USER="root"; $env:DB_PWD="你的密码"
mvn -pl bookkeep-web -am clean test      # 跑测试（需要 DB_USER/DB_PWD）
mvn -pl bookkeep-web spring-boot:run     # 开发运行
```

数据库初始化：`mysql -uroot -p --default-character-set=utf8mb4 -e "source sql/schema.sql"`。
已存在的库按需执行 `sql/migration_*.sql`，执行时**必须**带 `--default-character-set=utf8mb4`。

## 检查门槛

- 后端：`mvn -pl bookkeep-web -am clean test`（**必须 clean**：增量编译会掩盖符号错误）
- 前端：`npm run test` + `npm run build`