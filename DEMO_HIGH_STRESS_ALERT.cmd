@echo off
setlocal
title StressGuard high-stress demo
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\demo-high-stress-alert.ps1"
echo.
if errorlevel 1 (
    echo The alert could not be sent. Read the message above, then try again.
) else (
    echo You can close this window after confirming both notifications.
)
pause
