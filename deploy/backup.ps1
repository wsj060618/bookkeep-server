# 双击 backup.bat 调到这里：mysqldump 整库备份到 deploy\backup\，保留 14 天。

$deploy = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $deploy

if (-not (Test-Path (Join-Path $deploy 'env.ps1'))) {
    Write-Host '[错误] 找不到 deploy\env.ps1，先复制 env.ps1.example 并填写数据库账号密码' -ForegroundColor Red
    exit 1
}
. (Join-Path $deploy 'env.ps1')

$dump = Join-Path $env:MYSQL_HOME 'bin\mysqldump.exe'
if (-not (Test-Path $dump)) {
    Write-Host "[错误] 找不到 $dump，检查 env.ps1 的 MYSQL_HOME" -ForegroundColor Red
    exit 1
}

$dir = Join-Path $deploy 'backup'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$file = Join-Path $dir ("bookkeep_" + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.sql')
$errFile = Join-Path $dir 'dump.err'

Write-Host "备份到 $file ..."
# --single-transaction 不锁表；--default-character-set=utf8mb4 保证中文/emoji 不乱
$p = Start-Process -FilePath $dump `
    -ArgumentList "-u$($env:DB_USER)", "-p$($env:DB_PWD)", '--single-transaction', '--default-character-set=utf8mb4', '--databases', 'bookkeep' `
    -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $file -RedirectStandardError $errFile

if ($p.ExitCode -ne 0) {
    Write-Host '[错误] mysqldump 失败：' -ForegroundColor Red
    Get-Content $errFile -ErrorAction SilentlyContinue
    Remove-Item $file -Force -ErrorAction SilentlyContinue
    exit 1
}

$size = (Get-Item $file).Length
if ($size -lt 1) {
    Write-Host '[错误] 备份文件是空的，看 backup\dump.err' -ForegroundColor Red
    exit 1
}
Write-Host "备份完成：$([Math]::Round($size / 1KB, 1)) KB"

$deadline = (Get-Date).AddDays(-14)
Get-ChildItem $dir -Filter 'bookkeep_*.sql' |
    Where-Object { $_.LastWriteTime -lt $deadline } |
    ForEach-Object { Remove-Item $_.FullName -Force; Write-Host "清理旧备份 $($_.Name)" }

Write-Host ''
Write-Host '现有备份（新到旧）：'
Get-ChildItem $dir -Filter 'bookkeep_*.sql' | Sort-Object LastWriteTime -Descending |
    Select-Object -First 10 |
    ForEach-Object { Write-Host ("  {0}  {1:N1} KB" -f $_.Name, ($_.Length / 1KB)) }