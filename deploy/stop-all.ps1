# 双击 stop-all.bat 调到这里：只停自己起的进程（pid 文件里的 java + 本目录的 nginx）。

$deploy = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $deploy

$nginxHome = Join-Path $deploy 'nginx\runtime'
$nginxExe  = Join-Path $nginxHome 'nginx.exe'
$pidFile   = Join-Path $deploy 'run\bookkeep.pid'

Write-Host '=========================================================='
Write-Host '  bookkeep 单机停止'
Write-Host '=========================================================='

Write-Host '[1/2] 停止后端 ...'
if (-not (Test-Path $pidFile)) {
    Write-Host '  没有 run\bookkeep.pid，后端可能本来就没启动'
} else {
    $appPid = (Get-Content $pidFile -Raw).Trim()
    $proc = Get-Process -Id $appPid -ErrorAction SilentlyContinue
    # 只认 java.exe：pid 会被系统复用，直接 taskkill 有杀错进程的风险
    if ($proc -and $proc.ProcessName -eq 'java') {
        Stop-Process -Id $appPid -Force
        Write-Host "  已停止后端 PID $appPid"
    } else {
        Write-Host "  PID $appPid 已不是 java 进程（系统可能复用了这个号），跳过"
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

Write-Host '[2/2] 停止 Nginx ...'
if (-not (Test-Path $nginxExe)) {
    Write-Host '  找不到 nginx.exe，跳过'
} else {
    $ours = @(Get-Process nginx -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $nginxExe })
    if ($ours.Count -eq 0) {
        Write-Host '  Nginx 没有在运行'
    } else {
        & $nginxExe -p $nginxHome -s quit
        Start-Sleep -Seconds 1
        $left = @(Get-Process nginx -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $nginxExe })
        if ($left.Count -gt 0) {
            $left | Stop-Process -Force
            Write-Host '  Nginx 未在 1 秒内退出，已强制结束'
        } else {
            Write-Host '  Nginx 已退出'
        }
    }
}

Write-Host ''
Write-Host '已全部停止。'