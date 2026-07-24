@echo off
setlocal enabledelayedexpansion
rem Free port 8080 by stopping whatever is listening on it. Safe to run anytime:
rem if nothing is listening it just says so. Useful when a previous run was
rem stopped with Ctrl+C and left an orphaned java.exe holding the port.
set FOUND=0
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r /c:"TCP .*:8080 .*LISTENING"') do (
  echo Stopping process holding port 8080 ^(PID %%a^)...
  taskkill /F /PID %%a >nul 2>&1
  set FOUND=1
)
if "!FOUND!"=="0" echo Port 8080 is already free.
endlocal
