@echo off
setlocal
rem Start the app in this terminal's own desktop session. Running it here
rem (rather than from a service or a different session) is what lets "open in
rem Acrobat" hand statements to your already-running Acrobat instance.
rem
rem Wait for "Started PigPurchasesApplication", then browse to
rem http://localhost:8080 yourself. Ctrl+C stops it.
set SCRIPT_DIR=%~dp0
call "%SCRIPT_DIR%mvnw.cmd" -q -DskipTests spring-boot:run
