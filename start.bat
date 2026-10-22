@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Starting test-agent...
powershell -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
pause