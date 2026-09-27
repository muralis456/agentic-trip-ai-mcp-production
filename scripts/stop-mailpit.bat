@echo off
taskkill /IM mailpit.exe /F >nul 2>&1
if %ERRORLEVEL% EQU 0 (
  echo Mailpit stopped.
) else (
  echo Mailpit is not running.
)
