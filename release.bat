@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release.ps1" %*
if %ERRORLEVEL% NEQ 0 (
    exit /b %ERRORLEVEL%
)
