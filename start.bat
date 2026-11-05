@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem 所有参数原样传给 start.ps1，例如：
rem   start.bat -Build
rem   start.bat -Port 8081 -SkipDb
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo [test-agent] exited with code %EXIT_CODE%
    pause
)

endlocal & exit /b %EXIT_CODE%
