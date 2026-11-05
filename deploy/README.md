# 单机部署（Windows + MySQL + Nginx）

目标：双击 `start-all.bat` 就能用；重启电脑数据还在；备份能恢复到新库；手机同 WiFi 能打开。

```
浏览器 ──:80──> Nginx ──┬── /        → 前端 dist（另一个仓库 bookkeep-web）
                        └── /api/    → 127.0.0.1:8080（Spring Boot，只绑回环）
                                          └── MySQL 127.0.0.1:3306 / bookkeep
```

## 首次安装（约 30 分钟）

1. **JDK 17+**、**MySQL 8.0+**（用到了 `utf8mb4_0900_as_cs` 与生成列，5.7 不行）。
2. 建库：`sql/schema.sql`（自带 `CREATE DATABASE`，全新机器直接跑）。
   ```powershell
   & "D:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p -e "source D:/coding/bookkeep-server/sql/schema.sql"
   ```
   已经跑过老版本的库，还要依次跑 `sql/` 下的一次性 `migration_*.sql`。
3. **Nginx**（Windows 版 1.30.6）：下载 <https://nginx.org/download/nginx-1.30.6.zip>，
   解压后把 `nginx-1.30.6` 目录**改名成 `runtime`** 放到本目录的 `nginx/` 下：
   ```
   deploy/nginx/runtime/nginx.exe
   deploy/nginx/runtime/conf/...
   ```
   安装目录解压后不用改任何文件，配置一律走仓库里的 `nginx/nginx.conf`。
4. **配置**：复制 `env.ps1.example` 为 `env.ps1`，填 `DB_USER` / `DB_PWD` / `JWT_SECRET` / `MYSQL_HOME`。
   `env.ps1` 含数据库密码，已在 `.gitignore` 里。
5. **构建**：
   ```powershell
   # 后端（仓库根目录）
   mvn -pl bookkeep-web -am -DskipTests package      # 产物 bookkeep-web/target/bookkeep.jar
   # 前端（另一个仓库）
   cd D:\coding\bookkeep-web ; npm run build         # 产物 dist\index.html
   ```
6. **放行 80 端口入站**（管理员 PowerShell，只需一次；不放行则只能本机访问）：
   ```powershell
   netsh advfirewall firewall add rule name="bookkeep http" dir=in action=allow protocol=TCP localport=80
   ```

## 日常使用

| 操作 | 方式 |
|---|---|
| 启动 | 双击 `deploy\start-all.bat` |
| 停止 | 双击 `deploy\stop-all.bat` |
| 备份 | 双击 `deploy\backup.bat`（写 `deploy\backup\bookkeep_yyyyMMdd_HHmmss.sql`，保留 14 天） |
| 恢复 | `deploy\restore.bat`（不带参数用最新备份）或 `deploy\restore.bat deploy\backup\bookkeep_xxx.sql`（会覆盖现有数据） |
| 本机访问 | <http://localhost/> |
| 手机访问 | `http://本机局域网IP/`，IP 用 `ipconfig` 看「IPv4 地址」 |
| 日志 | `deploy\logs\bookkeep-server.log`、`bookkeep-server-error.log`、`console.err` |
| 忘密码 | 见下 |

`.bat` 只是启动器，逻辑都在同名 `.ps1` 里（cmd 读 UTF-8 中文批处理会错位，这条踩过坑，别把逻辑搬回 bat）。
`start-all` 做的是：检查 MySQL 服务 → 用 `env.ps1` 的 `FRONTEND_DIST` 生成 `nginx/nginx.generated.conf` → `nginx -t` 校验 → 起 Nginx → 以 `prod` profile 起后端（关掉 SQL DEBUG 日志）并等 8080 就绪（pid 写 `run/bookkeep.pid`）。

## 重置密码

```powershell
cd D:\coding\bookkeep-server\deploy
$env:DB_USER="root"; $env:DB_PWD="***"
java -jar ..\bookkeep-web\target\bookkeep.jar --reset-password=<用户名> <新密码>
```

- 服务**开着也能执行**：检测到 `--reset-password` 就不启 Web 容器，不会去抢 8080。
- 只能在这台机器上执行命令，本身就是身份证明，所以不需要旧密码；但**不要**把它做成 HTTP 接口。
- 密码规则与注册一致：6–32 位；用户名 4–20 位字母数字下划线。
- **改完密码，旧 token 立即全端失效**：`user.token_version` 自增 + JWT 的 `ver` 声明，过滤器比对不一致就按未认证处理。
  前端会收到真实 401，按既有逻辑清 token 回登录页。
- 环境变量没带就会因为 `${DB_USER}` 解析不到而启动失败——这是故意的，不提供默认数据库密码。

## 备份与恢复演练

```powershell
deploy\backup.bat          # → deploy\backup\bookkeep_yyyyMMdd_HHmmss.sql，保留 14 天
deploy\stop-all.bat        # 恢复前先停后端，避免连接占用
deploy\restore.bat         # 不带参数 = 用最新备份；会二次确认
deploy\start-all.bat
```

演练过的方式（PLAN §7 的口径）：备份 → 手工删掉一行流水 → `restore` → 行数回到原值、页面能看到那条流水。
`deploy\backup\dump.err` 里只有一条 `Using a password on the command line` 的警告，是 `mysqldump` 的正常提示，不是失败。

## 重启电脑后

- MySQL 服务是 `Automatic`，开机自启，数据在 MySQL 的 `datadir` 里，不随项目目录变化。
- Nginx 与后端**不会**自启，重启后双击 `start-all.bat` 即可——这是有意为之（不想留一个看不见的后台服务）。

## 排错

| 现象 | 原因 / 处理 |
|---|---|
| 页面 404、`/login` 刷新 404 | `nginx.generated.conf` 里的 `root` 不对，检查 `env.ps1` 的 `FRONTEND_DIST` 后重启脚本 |
| 页面能开但接口全 502 | 后端没起来，看 `deploy\logs\console.err`；或 8080 被 IDEA 里的实例占着 |
| 接口 404 且响应是 nginx 的 HTML | 反代把 `/api` 前缀剥掉了。`proxy_pass` 必须**不带**尾斜杠 |
| 手机打不开、本机能开 | 80 端口入站没放行（`netsh` 那条），或手机不在同一 WiFi |
| `netstat -ano \| findstr 8080` 显示 `0.0.0.0:8080` | `application.yml` 的 `server.address` 被改掉了，后端只应监听回环 |
| 起了两个后端 | 脚本会先检测 8080；但 `deploy\stop-all.bat` 只杀 `run\bookkeep.pid` 里的进程，IDEA 启的实例不归它管 |
| `mvn package` 报 `Unable to rename bookkeep.jar` | 后端正在运行，Windows 锁住了 jar。先 `stop-all.bat` 再打包（打包会先把 jar 截断，别在这个状态下启动） |