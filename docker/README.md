# 公网部署（Docker Compose）

目标：一台试用期服务器上跑 `mysql + app + nginx`，只暴露 80/443，HTTPS 访问。
本地开发仍然用 `deploy/` 的 Windows 直跑方案，**不需要装 Docker**。

## 0. 前置

- 服务器：2C2G 起，Ubuntu 22.04/24.04，Docker + compose 插件
- 域名：A 记录指向服务器 IP（没有域名可先用自签证书 + IP，但手机浏览器会警告）
- 安全组/防火墙：只放 22、80、443；**不要放 3306 和 8080**

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
- `APP_BASE_URL` 必须是公网 HTTPS 地址，否则找回密码邮件里的链接点不开
- `MAIL_FROM` 只填纯地址（必须与 `MAIL_USERNAME` 一致）；`MAIL_FROM_NAME` 控制收件人看到的发件人名字，默认「班级记账」

## 3. 证书

```bash
mkdir -p docker/certs
# Let's Encrypt（临时占用 80，签发后删掉 standalone 容器）
docker run --rm -p 80:80 -v $PWD/docker/certs:/etc/letsencrypt/live/your.domain \
  certbot/certbot certonly --standalone -d your.domain --agree-tos -m you@example.com
# 把 live/your.domain/ 下的 fullchain.pem、privkey.pem 放到 docker/certs/
```

或直接用 Cloudflare Origin Certificate（同样放到 `docker/certs/`）。

## 4. 启动

```bash
cd docker
docker compose up -d --build
docker compose ps                    # 三个服务都应 healthy / running
curl -I https://your.domain/         # 200 + HSTS/CSP 等安全头
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

**备份恢复演练**是 W4 验收项：dump → 新建库导入 → 核对行数与页面数据。

## 6. 安全清单（上线前逐项打勾）

- [ ] `DB_USER` 不是 root（compose 已默认建 `bookkeep` 专用账号，只授权本库）
- [ ] 3306 / 8080 没有 publish；`docker compose ps` 里看不到对外端口
- [ ] `JWT_SECRET` 随机且 ≥32 字符（prod 启动自检会拦默认值）
- [ ] 全站 HTTPS，HTTP 自动 301；HSTS 已开启
- [ ] 登录/注册限流生效：连续输错 5 次被拒；单 IP 每分钟 30 次（`application-prod.yml`）
- [ ] Nginx 透传 `X-Real-IP`（限流按真实 IP 算，否则形同虚设）
- [ ] 邮箱找回密码配置了 `MAIL_*` 并实测收到一封
- [ ] 备份能恢复到新库
- [ ] 服务器只开 22/80/443

## 7. 已知限制

- 限流是**单实例内存**计数：多副本部署会各算各的（本项目单机，不用考虑）
- 模板文件目录（§10.7 实现后）需要额外挂卷，否则容器重建会丢
- 试用期结束前把备份拉回本地，服务器到期数据会消失