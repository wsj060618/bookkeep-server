# 恢复：deploy\restore.bat [备份文件]；不带参数则用 backup\ 下最新的一个。
# 会覆盖现有数据库，务必先停后端（stop-all.bat）再做。

param([string]$File)

$deploy = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $deploy

if (-not (Test-Path (Join-Path $deploy 'env.ps1'))) {
    Write-Host '[错误] 找不到 deploy\env.ps1' -ForegroundColor Red
    exit 1
}
. (Join-Path $deploy 'env.ps1')

if (-not $File) {
    $File = Get-ChildItem (Join-Path $deploy 'backup') -Filter 'bookkeep_*.sql' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $File) {
        Write-Host '[错误] backup\ 下没有备份文件，先跑一次 backup.bat' -ForegroundColor Red
        exit 1
    }
}
if (-not (Test-Path $File)) {
    Write-Host "[错误] 找不到文件 $File" -ForegroundColor Red
    exit 1
}

$mysql = Join-Path $env:MYSQL_HOME 'bin\mysql.exe'
if (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) {
    Write-Host '[提醒] 后端还在运行，建议先 deploy\stop-all.bat（导入会覆盖数据，别让页面同时写）' -ForegroundColor Yellow
}
Write-Host "将把 $File 导入数据库 bookkeep，现有数据会被覆盖。"
$answer = Read-Host '确认继续？输入 y 回车'
if ($answer -notmatch '^(y|Y)$') {
    Write-Host '已取消'
    exit 1
}

# 用 stdin 喂文件：mysql 的 source 命令在 Windows 下会被盘符里的冒号坑（ERROR 1059）
$errFile = Join-Path $deploy 'backup\restore.err'
$p = Start-Process -FilePath $mysql `
    -ArgumentList "-u$($env:DB_USER)", "-p$($env:DB_PWD)" `
    -NoNewWindow -Wait -PassThru `
    -RedirectStandardInput (Resolve-Path $File).Path `
    -RedirectStandardOutput (Join-Path $deploy 'backup\restore.out') -RedirectStandardError $errFile

if ($p.ExitCode -ne 0) {
    Write-Host '[错误] 恢复失败：' -ForegroundColor Red
    Get-Content $errFile -ErrorAction SilentlyContinue
    exit 1
}
Write-Host '恢复完成。重新 start-all.bat，然后登录确认数据。'