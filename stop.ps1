<#
.SYNOPSIS
    停止占用指定端口的 test-agent 进程

.DESCRIPTION
    通过端口找到监听进程，确认后结束它。
    默认端口 8080；支持一次传多个端口，例如 -Port 8080,8081。

.PARAMETER Port
    要释放的端口，默认 8080

.PARAMETER Force
    不询问，直接结束进程

.PARAMETER Help
    显示帮助

.EXAMPLE
    .\stop.ps1
    停止占用 8080 的进程（会先询问）

.EXAMPLE
    .\stop.ps1 -Port 8081 -Force
    直接停止占用 8081 的进程

.EXAMPLE
    .\stop.ps1 -Port 8080,8090
    一次停止多个端口
#>
param(
    [int[]]$Port = @(8080),
    [switch]$Force,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

function Show-Help {
    Write-Host ""
    Write-Host "test-agent 停止脚本" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  .\stop.ps1                  停止 8080 端口的服务（会询问）"
    Write-Host "  .\stop.ps1 -Force          不询问，直接停止"
    Write-Host "  .\stop.ps1 -Port 8081      指定端口"
    Write-Host "  .\stop.ps1 -Port 8080,8090 一次停止多个端口"
    Write-Host ""
}

function Get-PortOwners([int]$port) {
    $pids = @()

    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $pids = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess
    }

    if (-not $pids -or $pids.Count -eq 0) {
        $lines = netstat -ano | Select-String "LISTENING" | Select-String ":$port\s"
        foreach ($line in $lines) {
            $parts = ($line.ToString().Trim() -split "\s+")
            $candidate = 0
            if ([int]::TryParse($parts[$parts.Length - 1], [ref]$candidate)) {
                $pids += $candidate
            }
        }
    }

    return ($pids | Where-Object { $_ -gt 0 } | Sort-Object -Unique)
}

function Wait-PortFree([int]$port, [int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (-not (Get-PortOwners $port)) { return $true }
        Start-Sleep -Seconds 1
    }
    return (-not (Get-PortOwners $port))
}

if ($Help) {
    Show-Help
    exit 0
}

$allPids = @()
foreach ($p in $Port) {
    $owners = Get-PortOwners $p
    if (-not $owners -or $owners.Count -eq 0) {
        Write-Host "[port $p] 没有监听进程" -ForegroundColor DarkGray
        continue
    }
    foreach ($ownerPid in $owners) {
        if ($allPids -notcontains $ownerPid) {
            $allPids += $ownerPid
            $process = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
            $name = if ($process) { $process.ProcessName } else { "unknown" }
            Write-Host "[port $p] PID $ownerPid ($name)" -ForegroundColor Yellow
        }
    }
}

if ($allPids.Count -eq 0) {
    Write-Host "没有需要停止的进程。" -ForegroundColor Green
    exit 0
}

if (-not $Force) {
    $answer = Read-Host "确认结束以上进程? [y/N]"
    if ($answer -notmatch '^(y|yes)$') {
        Write-Host "已取消。" -ForegroundColor DarkGray
        exit 0
    }
}

foreach ($ownerPid in $allPids) {
    $process = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "[PID $ownerPid] 已退出" -ForegroundColor DarkGray
        continue
    }
    try {
        Stop-Process -Id $ownerPid -Force -ErrorAction Stop
        Write-Host "[PID $ownerPid] 已停止 ($($process.ProcessName))" -ForegroundColor Green
    }
    catch {
        Write-Host "[PID $ownerPid] 停止失败: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "        可在管理员终端重试" -ForegroundColor Yellow
    }
}

foreach ($p in $Port) {
    if (Wait-PortFree $p 5) {
        Write-Host "[port $p] 已释放" -ForegroundColor Green
    }
    else {
        Write-Host "[port $p] 仍被占用，请检查是否还有其他进程监听" -ForegroundColor Yellow
    }
}
