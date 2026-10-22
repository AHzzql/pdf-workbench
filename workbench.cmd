@echo off
setlocal
where pwsh.exe >nul 2>nul
if errorlevel 1 (
  echo PowerShell 7.2 or later is required.
  exit /b 1
)
pwsh.exe -NoLogo -NoProfile -File "%~dp0deploy\Workbench.ps1" %*
exit /b %errorlevel%
