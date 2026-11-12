@echo off
rem Saves a NetworkControl diagnostics package (zip) to the Desktop. Works even if the window cannot open.
set "APP=%~dp0App\NetworkControl.exe"
if not exist "%APP%" set "APP=%ProgramFiles%\NetworkControl\App\NetworkControl.exe"
if not exist "%APP%" (
  echo NetworkControl.exe was not found.
  pause
  exit /b 1
)
echo Collecting diagnostics, please wait...
start "" /wait "%APP%" --export-diagnostics
echo.
echo Done. The file NetworkControl-diagnostics-*.zip is on your Desktop.
echo Please send it to the developer.
pause
