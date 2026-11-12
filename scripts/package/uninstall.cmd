@echo off
rem Uninstalls NetworkControl (keeps rules and logs; see README.txt to remove them as well).
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
rem Run a copy from %TEMP%: the installation folder (which may contain this script) is deleted.
copy /y "%~dp0uninstall-service.ps1" "%TEMP%\networkcontrol-uninstall.ps1" >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%TEMP%\networkcontrol-uninstall.ps1"
del "%TEMP%\networkcontrol-uninstall.ps1" >nul 2>&1
echo.
pause
