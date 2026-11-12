@echo off
rem Starts the portable interface. The app starts Service\NetworkControl.Service.exe itself (UAC prompt).
start "" "%~dp0App\NetworkControl.exe"
