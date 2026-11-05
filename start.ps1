<#
.SYNOPSIS
    test-agent 启动脚本（自动配置 JDK 25 / 数据库 / 端口检查）

.DESCRIPTION
    默认以开发模式启动（mvn spring-boot:run），改代码后重新运行即可。
    也可以打包后以 jar 方式启动。

.PARAMETER Build
    先执行 clean package 再以 jar 方式启动（改了大量依赖时使用）

.PARAMETER Jar
    以 jar 方式启动；jar 不存在或比源码旧时自动打包

.PARAMETER NoBuild
    配合 -Jar 使用：只运行已有 jar，绝不重新打包

.PARAMETER SkipDb
    跳过 Docker/数据库检查（使用外部 PostgreSQL 时）

.PARAMETER StartDocker
    Docker 守护进程未启动时，尝试自动启动 Docker Desktop

.PARAMETER Port
    应用端口，默认 8080

.PARAMETER Help
    显示帮助

.EXAMPLE
    .\start.ps1
    开发模式启动，自动启动数据库

.EXAMPLE
    .\start.ps1 -Build
    重新打包并以 jar 方式启动

.EXAMPLE
    .\start.ps1 -Port 8081 -SkipDb
    使用外部数据库，在 8081 端口启动
#>
param(
    [switch]$Build,
    [switch]$Jar,
    [switch]$NoBuild,
    [switch]$SkipDb,
    [switch]$StartDocker,
    [int]$Port = 8080,
    [switch]$Help
)

# 用 Continue：java/docker 等原生命令把正常输出写到 stderr，
# 在 Stop 模式下会被当成致命错误，导致脚本误退出。
$ErrorActionPreference = "Continue"

# ======================== 配置 ========================
$ProjectDir = $PSScriptRoot
$ArtifactId = "test-agent"
$JarFile    = Join-Path $ProjectDir "target\$ArtifactId-1.0.0.jar"
# ======================================================

function Write-Step([string]$message) {
    Write-Host "[$ArtifactId] $message" -ForegroundColor Cyan
}

function Fail([string]$message, [string]$hint) {
    Write-Host ""
    Write-Host "[ERROR] $message" -ForegroundColor Red
    if ($hint) { Write-Host "        $hint" -ForegroundColor Yellow }
    Write-Host ""
    exit 1
}

function Show-Help {
    Write-Host ""
    Write-Host "test-agent 启动脚本" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  .\start.ps1                 开发模式启动（默认，自动编译）"
    Write-Host "  .\start.ps1 -Build          重新打包后以 jar 方式启动"
    Write-Host "  .\start.ps1 -Jar            jar 方式启动（缺失/过期时自动打包）"
    Write-Host "  .\start.ps1 -Jar -NoBuild   只运行已有 jar"
    Write-Host "  .\start.ps1 -SkipDb         跳过 Docker 数据库检查"
    Write-Host "  .\start.ps1 -StartDocker    自动启动 Docker Desktop"
    Write-Host "  .\start.ps1 -Port 8081      指定端口"
    Write-Host ""
    Write-Host "  停止服务： .\stop.ps1 -Port 8080"
    Write-Host "  详细说明： docs\startup-guide.md"
    Write-Host ""
}

function Get-JavaVersionLine([string]$javaExe) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $javaExe
    $psi.Arguments = "-version"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardOutput = $true
    $psi.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($psi)
    $line = $process.StandardError.ReadLine()
    $process.WaitForExit()
    return $line
}

function Resolve-JavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]

    $candidates.Add("C:\Program Files\Java\jdk-25.0.3")
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }

    foreach ($root in @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft")) {
        if (Test-Path $root) {
            Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match "25" } |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\java.exe"))) {
            return $candidate
        }
    }

    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        $versionLine = Get-JavaVersionLine $javaCmd.Source
        if ($versionLine -match 'version "(\d+)') {
            if ([int]$Matches[1] -ge 25) {
                return (Split-Path (Split-Path $javaCmd.Source -Parent) -Parent)
            }
        }
    }
    return $null
}

function Set-Jdk([string]$javaHome) {
    $env:JAVA_HOME = $javaHome
    $env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH
    Write-Step "JDK: $javaHome"
    $versionLine = Get-JavaVersionLine (Join-Path $javaHome "bin\java.exe")
    if ($versionLine) { Write-Host "       $versionLine" -ForegroundColor DarkGray }
}

function Resolve-Maven {
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvnCmd) {
        Fail "未找到 Maven（mvn）" "请安装 Maven 3.9+，或把它加入 PATH 后重开终端"
    }
    return $mvnCmd.Source
}

function Resolve-Docker {
    $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($dockerCmd) { return $dockerCmd.Source }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Programs\DockerDesktop\resources\bin\docker.exe"),
        "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function Test-DockerDaemon([string]$docker) {
    & $docker info *> $null
    return ($LASTEXITCODE -eq 0)
}

function Start-DockerDesktop {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Programs\DockerDesktop\Docker Desktop.exe"),
        "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    )
    foreach ($exe in $candidates) {
        if (Test-Path $exe) {
            Write-Step "正在启动 Docker Desktop..."
            Start-Process -FilePath $exe | Out-Null
            return $true
        }
    }
    return $false
}

function Wait-DockerDaemon([string]$docker) {
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-DockerDaemon $docker) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Ensure-Database([string]$docker) {
    Push-Location $ProjectDir
    try {
        $composeFile = Join-Path $ProjectDir "docker-compose.yml"
        if (-not (Test-Path $composeFile)) {
            Write-Host "[DB] 未找到 docker-compose.yml，跳过数据库启动" -ForegroundColor Yellow
            return
        }

        Write-Step "启动 PostgreSQL 容器..."
        & $docker compose up -d 2>&1 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkGray }
        if ($LASTEXITCODE -ne 0) {
            Fail "docker compose up -d 失败" "检查 Docker Desktop 是否正常运行，或执行 docker compose logs 查看原因"
        }

        Write-Step "等待 PostgreSQL 就绪..."
        for ($i = 0; $i -lt 30; $i++) {
            & $docker compose exec -T postgres pg_isready -U postgres *> $null
            if ($LASTEXITCODE -eq 0) {
                Write-Step "PostgreSQL 已就绪 (localhost:5432)"
                return
            }
            Start-Sleep -Seconds 1
        }
        Fail "PostgreSQL 30 秒内未就绪" "执行 docker compose logs postgres 查看原因"
    }
    finally {
        Pop-Location
    }
}

function Get-PortOwner([int]$port) {
    $connection = $null
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $connection = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
    }
    if (-not $connection) {
        $line = netstat -ano | Select-String "LISTENING" | Select-String ":$port\s" | Select-Object -First 1
        if ($line) {
            $parts = ($line.ToString().Trim() -split "\s+")
            $owner = [int]$parts[$parts.Length - 1]
            return [pscustomobject]@{ ProcessId = $owner; ProcessName = "unknown" }
        }
        return $null
    }
    $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
    $name = if ($process) { $process.ProcessName } else { "unknown" }
    return [pscustomobject]@{ ProcessId = $connection.OwningProcess; ProcessName = $name }
}

function Assert-PortFree([int]$port) {
    $owner = Get-PortOwner $port
    if ($owner) {
        Fail "端口 $port 已被占用：PID $($owner.ProcessId) ($($owner.ProcessName))" "停止占用进程： .\stop.ps1 -Port $port"
    }
    Write-Step "端口 $port 可用"
}

function Test-JarStale {
    if (-not (Test-Path $JarFile)) { return $true }
    $jarTime = (Get-Item $JarFile).LastWriteTimeUtc

    $items = @()
    $pom = Join-Path $ProjectDir "pom.xml"
    if (Test-Path $pom) { $items += Get-Item $pom }
    $src = Join-Path $ProjectDir "src"
    if (Test-Path $src) { $items += Get-ChildItem $src -Recurse -File -ErrorAction SilentlyContinue }
    if (-not $items.Count) { return $false }

    $newest = $items | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    return ($newest.LastWriteTimeUtc -gt $jarTime)
}

function Invoke-JarBuild {
    Push-Location $ProjectDir
    try {
        Write-Step "执行 mvn clean package -DskipTests ..."
        & $mvn -B clean package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Fail "Maven 打包失败" "先单独执行 mvn -B package -DskipTests 查看完整报错"
        }
        Write-Step "打包完成: $JarFile"
    }
    finally {
        Pop-Location
    }
}

# ======================== 主流程 ========================

if ($Help) {
    Show-Help
    exit 0
}

Set-Location $ProjectDir

$javaHome = Resolve-JavaHome
if (-not $javaHome) {
    Fail "未找到 JDK 25" "安装 JDK 25，或把 JAVA_HOME 指向 JDK 25 后重开终端；当前默认 Java 可能是 1.8"
}
Set-Jdk $javaHome
$mvn = Resolve-Maven

$useJar = $Jar -or $Build
if ($NoBuild -and -not $useJar) {
    Write-Host "[WARN] -NoBuild 需要配合 -Jar 或 -Build 使用，本次按开发模式启动" -ForegroundColor Yellow
}

if (-not $SkipDb) {
    $docker = Resolve-Docker
    if (-not $docker) {
        Write-Host "[DB] 未找到 Docker，跳过数据库检查（若使用外部 PostgreSQL 可忽略）" -ForegroundColor Yellow
    }
    else {
        $env:PATH = (Split-Path $docker -Parent) + ";" + $env:PATH
        if (-not (Test-DockerDaemon $docker)) {
            if ($StartDocker) {
                if (-not (Start-DockerDesktop)) {
                    Fail "Docker 守护进程未运行，且未找到 Docker Desktop 安装路径" "手动打开 Docker Desktop 后重试"
                }
                if (-not (Wait-DockerDaemon $docker)) {
                    Fail "Docker Desktop 启动超时" "等待 Docker Desktop 完全启动后重试"
                }
            }
            else {
                Fail "Docker 守护进程未运行" "先打开 Docker Desktop，或加 -StartDocker 参数自动启动"
            }
        }
        Ensure-Database $docker
    }
}
else {
    Write-Step "已跳过 Docker/数据库检查 (-SkipDb)"
}

Assert-PortFree $Port

Write-Host "--------------------------------------------" -ForegroundColor DarkGray

if ($useJar) {
    $jarStale = Test-JarStale
    if ($NoBuild -and -not (Test-Path $JarFile)) {
        Fail "未找到 jar：$JarFile" "去掉 -NoBuild，或先执行 .\start.ps1 -Build"
    }
    if ($NoBuild -and $jarStale) {
        Write-Host "[WARN] 现有 jar 比源码旧，-NoBuild 将运行旧版本" -ForegroundColor Yellow
        Write-Host "       如需包含最新代码： .\start.ps1 -Build" -ForegroundColor Yellow
    }
    $needBuild = $Build -or ($jarStale -and -not $NoBuild)
    if ($needBuild) {
        Invoke-JarBuild
    }
    else {
        Write-Step "使用已有 jar（未检测到过期）"
    }

    Write-Step "以 jar 方式启动: http://localhost:$Port"
    Write-Host "--------------------------------------------" -ForegroundColor DarkGray
    & java -jar $JarFile "--server.port=$Port"
}
else {
    Write-Step "开发模式启动: http://localhost:$Port"
    Write-Host "--------------------------------------------" -ForegroundColor DarkGray
    & $mvn -B spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port"
}

exit $LASTEXITCODE
