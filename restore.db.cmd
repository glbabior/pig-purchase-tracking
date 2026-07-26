@echo off
setlocal enabledelayedexpansion
rem Restore a database backup (an H2 SCRIPT .sql produced by BackupService) into
rem the live database. Usage:
rem     restore.db.cmd "C:\Users\<you>\pigpurchases-backups\pigpurchases-YYYY-MM-DD.sql"
rem
rem It stops anything on port 8080, keeps a safety copy of the current live db,
rem then loads the chosen dump into a fresh live database. Start launch.cmd after.

if "%~1"=="" (
  echo Usage: restore.db.cmd "path\to\pigpurchases-YYYY-MM-DD.sql"
  echo Backups live in "%USERPROFILE%\pigpurchases-backups".
  exit /b 1
)
if not exist "%~1" (
  echo Backup file not found: %~1
  exit /b 1
)

set SCRIPT_DIR=%~dp0
rem Free port 8080 / stop a running instance so the db file is not locked.
call "%SCRIPT_DIR%stop.cmd"

rem Find the newest H2 jar in the local Maven cache.
set H2JAR=
for /f "delims=" %%J in ('powershell -NoProfile -Command "(Get-ChildItem \"$env:USERPROFILE\.m2\repository\com\h2database\h2\*\h2-*.jar\" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1).FullName"') do set "H2JAR=%%J"
if not defined H2JAR (
  echo Could not find an H2 jar under "%USERPROFILE%\.m2". Build once with mvnw first.
  exit /b 1
)

set "LIVE=%USERPROFILE%\.pigpurchases\pig-purchases-db"
if exist "%LIVE%.mv.db" (
  echo Keeping safety copy of current live db...
  copy /Y "%LIVE%.mv.db" "%LIVE%.pre-restore.mv.dbbak" >nul
  del /Q "%LIVE%.mv.db"
)

echo Restoring "%~1" into "%LIVE%.mv.db" ...
java -cp "%H2JAR%" org.h2.tools.RunScript -url "jdbc:h2:file:%LIVE%" -user sa -password "" -script "%~1"
if errorlevel 1 (
  echo RESTORE FAILED. The safety copy is at "%LIVE%.pre-restore.mv.dbbak".
  exit /b 1
)
echo Done. Restored. Now run launch.cmd and verify your data.
endlocal
