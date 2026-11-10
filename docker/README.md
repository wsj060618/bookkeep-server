# 公网部署（Docker Compose）

目标：一台试用期服务器上跑 `mysql + app + nginx`，只暴露 80/443，访问地址 `http://服务器IP/`。
本地开发仍然用 `deploy/` 的 Windows 直跑方案，**不需要装 Docker**。

> **默认没有 HTTPS，这是有意的**：国内云服务器的域名**必须备案**才放行（未备案会被运营商拦成
> 「网站未备案」），而裸 IP 又签不到可信证书（Let's Encrypt 不给 IP 签）。
> 所以默认按「IP + HTTP」部署（`docker/nginx.conf`），安全补偿是：登录限流、
> JWT 有效期 1 天、8080 不 publish、3306 只绑回环、只开 80。**别在这个环境里用常用密码。**
> 真要 HTTPS 只有两条路：① 备案域名 + 国内服务器 ② 境外/香港服务器 + 任意域名
> —— 那时把 `NGINX_CONF` 换成 `./nginx.tls.conf` 并放好 `certs/`。

## 0. 前置

- 服务器：2C2G 起，Ubuntu 22.04/24.04，Docker + compose 插件
- 域名：**不需要**（裸 IP 访问）；有备案域名或境外服务器时才走 TLS
- 安全组/防火墙：只放 22、80（走 HTTPS 再放 443）；**不要放 3306 和 8080**
  （3306 只绑在服务器回环上，给 SSH 隧道连库用；8080 只在 compose 内网）

## 1. 构建产物（在本地或 CI）

```powershell
# 后端
mvn -pl bookkeep-web -am -DskipTests package        # → bookkeep-web/target/bookkeep.jar
# 前端（另一个仓库）
cd D:\coding\bookkeep-web ; npm run build           # → dist
```

把仓库和前端 `dist` 一起传到服务器（或 `FRONTEND_DIST` 指到服务器上的前端目录）。

## 2. 配置

```bash
cp docker/.env.example docker/.env
vim docker/.env          # 填 MYSQL_ROOT_PASSWORD / DB_PWD / JWT_SECRET / APP_BASE_URL / MAIL_*
```

- `JWT_SECRET` 必须 ≥32 字符；prod profile 下若仍是仓库默认值，应用**拒绝启动**
- `APP_BASE_URL`：**没有域名时填 `http://你的服务器IP`**（找回密码邮件里的链接走这个）
- `NGINX_CONF`：默认 `./nginx.conf`（纯 HTTP）；有备案域名/境外服务器时改 `./nginx.tls.conf`
- `MAIL_FROM` 只填纯地址（必须与 `MAIL_USERNAME` 一致）；`MAIL_FROM_NAME` 控制收件人看到的发件人名字，默认项目名「bookkeep」

## 3. 证书（只有走 TLS 时才需要）

```bash
mkdir -p docker/certs
# 前提：域名已备案（国内）或服务器在境外；Let's Encrypt 临时占用 80 签发
docker run --rm -p 80:80 -v $PWD/docker/certs:/etc/letsencrypt/live/your.domain \
  certbot/certbot certonly --standalone -d your.domain --agree-tos -m you@example.com
# 把 live/your.domain/ 下的 fullchain.pem、privkey.pem 放到 docker/certs/
```

或直接用 Cloudflare Origin Certificate（同样放到 `docker/certs/`）。
**没有域名就别做这一步**，保持默认的 HTTP 配置即可。

## 4. 启动

```bash
cd docker
docker compose up -d --build
docker compose ps                    # 三个服务都应 healthy / running
curl -I http://服务器IP/             # 200 + CSP/nosniff 等安全头（没有 HSTS，因为不是 HTTPS）
```

首次启动、`db_data` 为空时，MySQL 会自动执行 `sql/schema.sql` 建库建表。
**已有数据的库**不要靠这个：手动导入 dump，或依次跑 `sql/migration_*.sql`。

## 5. 日常运维

```bash
docker compose logs -f app           # 应用日志
docker compose exec mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --default-character-set=utf8mb4 bookkeep' > backup_$(date +%F).sql
docker compose restart app           # 改 env 后重启
docker compose down                  # 停（数据卷保留）
```

### 连库改数据（MySQL 只绑了 127.0.0.1）

命令行（在服务器上）：

```bash
cd /opt/bookkeep-server/docker
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" bookkeep'
```

图形化（Navicat / DBeaver）：在本机开隧道，把服务器本机的 3306 映射到本地 3307：

```powershell
ssh -L 3307:127.0.0.1:3306 root@服务器IP
```

然后连 `127.0.0.1:3307`，超时 `bookkeep` / 密码 `.env` 里的 `DB_PWD`。
**云安全组不要开 3306**——端口只绑回环，开了也没用，但会让人误以为对外放开。

**备份恢复演练**是 W4 验收项：dump → 新建库导入 → 核对行数与页面数据。
票据图片在命名卷 `receipts_data` 里，**必须单独备份**，否则恢复后页面有记录、图全裂：

```bash
docker compose exec app tar czf - -C /app/data receipts > receipts_$(date +%F).tgz   # 备份
docker compose cp receipts_2026-11-10.tgz app:/tmp/ && \
docker compose exec app tar xzf /tmp/receipts_2026-11-10.tgz -C /app/data            # 恢复
```

## 6. 安全清单（上线前逐项打勾）

- [ ] `DB_USER` 不是 root（compose 已默认建 `bookkeep` 专用账号，只授权本库）
- [ ] 3306 只绑 `127.0.0.1`（给 SSH 隧道用），8080 不 publish；**云安全组也别开 3306/8080**
      验证：`ss -lntp | grep -E '3306|8080'` 应只有 `127.0.0.1:3306`，没有 8080
- [ ] `JWT_SECRET` 随机且 ≥32 字符（prod 启动自检会拦默认值）
- [ ] **明文传输已确认接受**：没有域名 → 没有 TLS，登录密码与 JWT 在公网上是明文。
      补偿：只在私人网络下登录管理端、`JWT_SECRET` 随机、`JWT_EXPIRE=86400`、不用常用密码
- [ ] 登录/注册限流生效：连续输错 5 次被拒；单 IP 每分钟 30 次（`application-prod.yml`）
- [ ] Nginx 透传 `X-Real-IP`（限流按真实 IP 算，否则形同虚设）
- [ ] 邮箱找回密码配置了 `MAIL_*` 并实测收到一封（链接指向 `http://IP`）
- [ ] 备份能恢复到新库，票据包也能恢复
- [ ] 服务器只开 22/80（走 HTTPS 才开 443）

## 7. 已知限制

- 限流是**单实例内存**计数：多副本部署会各算各的（本项目单机，不用考虑）
- 模板文件目录（§10.7 实现后）需要额外挂卷，否则容器重建会丢
- 试用期结束前把备份拉回本地，服务器到期数据会消失