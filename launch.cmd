@echo off
setlocal
set SCRIPT_DIR=%~dp0
set MAVEN_BIN=%SCRIPT_DIR%maven\apache-maven-3.9.9\bin\mvn.cmd
call "%MAVEN_BIN%" -q -DskipTests spring-boot:run
