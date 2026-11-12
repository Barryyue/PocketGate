@echo off
rem Installs NetworkControl from this package. Requests administrator rights (UAC) if needed.
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
rem Files extracted from a downloaded zip carry the "mark of the web"; unblock them first.
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath '%~dp0' -Recurse | Unblock-File"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-service.ps1"
echo.
pause
