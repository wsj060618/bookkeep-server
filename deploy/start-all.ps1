# 双击 start-all.bat 调到这里：检查环境 → 生成 nginx 配置 → 起 Nginx → 起后端并等 8080 就绪。

$deploy = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $deploy
$ErrorActionPreference = 'Stop'

function Fail([string]$msg) {
    Write-Host "[错误] $msg" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path (Join-Path $deploy 'env.ps1'))) {
    Fail '找不到 deploy\env.ps1，请复制 env.ps1.example 为 env.ps1 并填好数据库账号密码'
}
. (Join-Path $deploy 'env.ps1')

$nginxHome = Join-Path $deploy 'nginx\runtime'
$nginxExe  = Join-Path $nginxHome 'nginx.exe'
$nginxTpl  = Join-Path $deploy 'nginx\nginx.conf'
$nginxConf = Join-Path $deploy 'nginx\nginx.generated.conf'
$jar       = Join-Path $deploy '..\bookkeep-web\target\bookkeep.jar'
$pidFile   = Join-Path $deploy 'run\bookkeep.pid'

if (-not (Test-Path $nginxExe)) { Fail "找不到 $nginxExe，见 deploy\README.md「首次安装」第 3 步" }
if (-not (Test-Path $jar)) { Fail "找不到 $jar，先在仓库根目录执行 mvn -pl bookkeep-web -am -DskipTests package" }
if (-not (Test-Path (Join-Path $env:FRONTEND_DIST 'index.html'))) { Fail "找不到 $($env:FRONTEND_DIST)\index.html，先在 bookkeep-web 执行 npm run build" }
if (-not (Test-Path (Join-Path $env:MYSQL_HOME 'bin\mysql.exe'))) { Fail "找不到 mysql.exe，检查 env.ps1 的 MYSQL_HOME" }

$mysql = Get-Service -Name MySQL84 -ErrorAction SilentlyContinue
if (-not $mysql -or $mysql.Status -ne 'Running') {
    Fail 'MySQL 服务 MySQL84 没有运行，先 net start MySQL84（或 services.msc 里启动）'
}

Write-Host '=========================================================='
Write-Host '  bookkeep 单机启动'
Write-Host '=========================================================='

# ---------- 1. 生成 nginx 配置（前端目录只在 env.ps1 里写一次） ----------
Write-Host '[1/3] 生成 nginx 配置 ...'
$tpl = [IO.File]::ReadAllText($nginxTpl)
$tpl = $tpl.Replace('__FRONTEND_DIST__', ($env:FRONTEND_DIST -replace '\\', '/'))
[IO.File]::WriteAllText($nginxConf, $tpl, (New-Object Text.UTF8Encoding($false)))

& $nginxExe -p $nginxHome -c $nginxConf -t
if ($LASTEXITCODE -ne 0) { Fail 'nginx 配置检查失败，上面的报错就是原因' }

# ---------- 2. 起 Nginx ----------
if (Get-Process nginx -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $nginxExe }) {
    Write-Host '[2/3] Nginx 已在运行，跳过'
} else {
    if (Get-NetTCPConnection -State Listen -LocalPort 80 -ErrorAction SilentlyContinue) {
        Fail '80 端口被占用，Nginx 起不来。用 netstat -ano | findstr ":80 " 看是谁'
    }
    Write-Host '[2/3] 启动 Nginx ...'
    # nginx 自己写 logs/error.log、启动后脱离控制台；这里不要再重定向，
    # 否则子进程会继承启动器的句柄，脚本退了窗口却关不掉
    Start-Process -FilePath $nginxExe -ArgumentList '-p', $nginxHome, '-c', $nginxConf -WindowStyle Hidden
    Start-Sleep -Seconds 1
    if (-not (Get-NetTCPConnection -State Listen -LocalPort 80 -ErrorAction SilentlyContinue)) {
        Fail 'Nginx 没起来，看 deploy\nginx\runtime\logs\error.log'
    }
}

# ---------- 3. 起后端 ----------
if (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) {
    Fail '8080 已有进程在监听：后端可能已在运行，或 IDEA 里还开着 BookkeepApplication'
}
New-Item -ItemType Directory -Force -Path (Join-Path $deploy 'logs'), (Join-Path $deploy 'run') | Out-Null

Write-Host '[3/3] 启动后端 ...'
$proc = Start-Process -FilePath $env:JAVA_EXE `
    -ArgumentList '-jar', "`"$jar`"", '--spring.profiles.active=prod' `
    -WorkingDirectory $deploy `
    -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $deploy 'logs\console.out') `
    -RedirectStandardError (Join-Path $deploy 'logs\console.err')

$deadline = (Get-Date).AddSeconds(60)
$up = $false
while ((Get-Date) -lt $deadline) {
    if ($proc.HasExited) { break }
    if (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) { $up = $true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $up) {
    Write-Host '[错误] 后端没起来，console.err 末尾：' -ForegroundColor Red
    Get-Content (Join-Path $deploy 'logs\console.err') -Tail 20 -ErrorAction SilentlyContinue
    exit 1
}
Set-Content -Path $pidFile -Value $proc.Id -Encoding ascii

$ips = @(Get-NetIPConfiguration -ErrorAction SilentlyContinue |
    Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address } |
    ForEach-Object { $_.IPv4Address.IPAddress })

Write-Host ''
Write-Host '启动完成：'
Write-Host '  本机访问     http://localhost/'
foreach ($ip in $ips) { Write-Host "  手机/同 WiFi http://$ip/" }
Write-Host '  停止服务     deploy\stop-all.bat'
Write-Host '  日志         deploy\logs\bookkeep-server.log'
Write-Host ''
Write-Host '手机首次访问需要放行 80 端口入站（管理员 PowerShell，只需一次）：' -ForegroundColor Yellow
Write-Host '  netsh advfirewall firewall add rule name="bookkeep http" dir=in action=allow protocol=TCP localport=80' -ForegroundColor Yellow