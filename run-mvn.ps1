<#
.SYNOPSIS
    用 JDK 25 执行任意 Maven 命令的包装脚本

.DESCRIPTION
    系统默认 java 可能是 1.8，直接敲 mvn 会编译失败。
    本脚本自动定位 JDK 25 并设置 JAVA_HOME/PATH，然后把参数透传给 mvn。

.EXAMPLE
    .\run-mvn.ps1 -v
    .\run-mvn.ps1 clean package -DskipTests
    .\run-mvn.ps1 spring-boot:run
    .\run-mvn.ps1 test -Dtest=TextChunkerTest
#>
# 故意不声明 param 块：这样 -v、-Dxxx 等 Maven 参数会原样落在 $args 里
$MavenArgs = $args

if ($MavenArgs.Count -eq 0) {
    Write-Host "用法: .\run-mvn.ps1 <maven 参数>" -ForegroundColor Cyan
    Write-Host "示例: .\run-mvn.ps1 -v"
    Write-Host "      .\run-mvn.ps1 clean package -DskipTests"
    Write-Host "      .\run-mvn.ps1 spring-boot:run"
    exit 0
}

$ErrorActionPreference = "Continue"
$ProjectDir = $PSScriptRoot

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

$javaHome = Resolve-JavaHome
if (-not $javaHome) {
    Write-Host "[ERROR] 未找到 JDK 25" -ForegroundColor Red
    Write-Host "        安装 JDK 25，或把 JAVA_HOME 指向 JDK 25 后重开终端" -ForegroundColor Yellow
    exit 1
}

$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH

$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCmd) {
    Write-Host "[ERROR] 未找到 Maven（mvn），请安装 Maven 3.9+ 并加入 PATH" -ForegroundColor Red
    exit 1
}

Write-Host "JAVA_HOME = $env:JAVA_HOME" -ForegroundColor DarkGray
$versionLine = Get-JavaVersionLine (Join-Path $javaHome "bin\java.exe")
if ($versionLine) { Write-Host "java      = $versionLine" -ForegroundColor DarkGray }
Write-Host "--------------------------------------------" -ForegroundColor DarkGray

Push-Location $ProjectDir
try {
    & $mvnCmd.Source @MavenArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
