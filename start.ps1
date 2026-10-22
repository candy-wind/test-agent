<#
.SYNOPSIS
    test-agent Spring Boot startup script
.DESCRIPTION
    Auto-configures JDK 25, compiles and starts the Spring Boot app.
    Usage:
      .\start.ps1              # Start with existing jar (build if missing)
      .\start.ps1 -Build       # Force rebuild then start
      .\start.ps1 -Jar         # Start from existing jar only (skip build)
#>
param(
    [switch]$Build,
    [switch]$Jar
)

# ======================== Config ========================
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$JavaHome   = "C:\Program Files\Java\jdk-25.0.3"
$JarFile    = Join-Path $ProjectDir "target\test-agent-1.0.0.jar"
$ArtifactId = "test-agent"
# ========================================================

Set-Location $ProjectDir

# Setup JDK 25
$env:JAVA_HOME = $JavaHome
$env:PATH      = "$JavaHome\bin;$env:PATH"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Project: $ArtifactId" -ForegroundColor Cyan
Write-Host "  Dir:     $ProjectDir" -ForegroundColor Cyan
Write-Host "  JDK:     $JavaHome" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
java -version
Write-Host "--------------------------------------------"

# -Jar mode: run existing jar directly
if ($Jar) {
    if (-not (Test-Path $JarFile)) {
        Write-Host "[ERROR] Jar not found: $JarFile" -ForegroundColor Red
        Write-Host "Run: .\start.ps1 -Build" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "[START] Running from existing jar..." -ForegroundColor Green
    java -jar $JarFile
    exit 0
}

# Decide if rebuild is needed
$NeedBuild = $Build -or (-not (Test-Path $JarFile))

if ($NeedBuild) {
    Write-Host "[BUILD] Maven compile..." -ForegroundColor Yellow
    mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Maven build failed!" -ForegroundColor Red
        exit 1
    }
    Write-Host "[BUILD] Done." -ForegroundColor Green
}

# Start the app
Write-Host "[START] Launching Spring Boot..." -ForegroundColor Green
Write-Host "URL: http://localhost:8080" -ForegroundColor Cyan
Write-Host "Press Ctrl+C to stop" -ForegroundColor DarkGray
Write-Host "--------------------------------------------"
java -jar $JarFile