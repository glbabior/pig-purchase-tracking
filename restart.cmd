@echo off
setlocal
rem Cleanly stop any running instance (freeing port 8080), then start fresh.
rem This is the terminal equivalent of the Settings "Rebuild & restart" button;
rem use it for the very first launch, or any time the app isn't running.
set SCRIPT_DIR=%~dp0
call "%SCRIPT_DIR%stop.cmd"
call "%SCRIPT_DIR%launch.cmd"
endlocal
