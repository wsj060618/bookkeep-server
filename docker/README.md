# docker/

云端部署的容器化文件。**完整部署步骤见 [../docs/部署文档.md](../docs/部署文档.md)**，这里只记每个文件的职责。

| 文件 | 作用 |
|---|---|
| `Dockerfile` | 运行镜像：只 `COPY bookkeep-web/target/bookkeep.jar`，非 root 用户，`--spring.profiles.active=prod`。构建在本地完成，镜像里没有 Maven |
| `docker-compose.yml` | 三个服务：`mysql`（只绑 `127.0.0.1:3306`，给 SSH 隧道用）、`app`（不 publish）、`nginx`（80/443）。只有 `db_data` 为空时才会跑 `sql/schema.sql` |
| `nginx.conf` | 纯 HTTP：静态文件 + `/api` 反代、认证接口限流、CSP（`img-src` 必须含 `blob:`） |
| `nginx.tls.conf` | 备案域名 / 境外服务器才用，通过 `.env` 的 `NGINX_CONF` 切换 |
| `.env.example` | 配置模板，复制成 `.env` 后填 |
| `certs/` | 走 TLS 时放证书（目录不存在不影响 HTTP 模式） |

常用命令：

```bash
cd docker
docker compose up -d --build
docker compose ps
docker compose logs -f app
```