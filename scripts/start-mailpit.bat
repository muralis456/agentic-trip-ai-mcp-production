@echo off
setlocal
set "MAILPIT_EXE=%~dp0..\mailpit-windows-amd64\mailpit.exe"
if not exist "%MAILPIT_EXE%" set "MAILPIT_EXE=C:\softwares\mailpit-windows-amd64\mailpit.exe"
if not exist "%MAILPIT_EXE%" (
  echo Mailpit executable not found.
  echo Set MAILPIT_EXE to the full path of mailpit.exe.
  exit /b 1
)
set "MAILPIT_DATABASE=%~dp0..\data\mailpit\mailpit.db"
if not exist "%~dp0..\data\mailpit" mkdir "%~dp0..\data\mailpit"
start "Mailpit" "%MAILPIT_EXE%" --database "%MAILPIT_DATABASE%" --max 0
echo Mailpit started with persistent database: %MAILPIT_DATABASE%
echo SMTP: localhost:1025  UI: http://localhost:8025
endlocal
