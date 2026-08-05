@echo off
setlocal
rem Run the app in demonstration mode, with no data of your own.
rem
rem On first start this generates three months of Northwind Bank statements (a bank
rem that does not exist, in a format this project invents), a statement source pointing
rem at them, and budget entries whose hints will place most of what they contain.
rem
rem Everything it touches is disposable and separate from a normal launch:
rem   database   %USERPROFILE%\.pigpurchases-demo\demo-db
rem   statements %USERPROFILE%\.pigpurchases-demo\statements
rem   backups    disabled
rem   AI         disabled, so it makes no API calls and costs nothing
rem
rem Delete %USERPROFILE%\.pigpurchases-demo to start over.
rem
rem Wait for "Started PigPurchasesApplication", then open http://localhost:8080 and
rem work left to right: Ingest to load a statement, Mapping to categorize it, Spend to
rem see the result. Two merchants in every statement match no hint on purpose, so they
rem land in "Other" for you to review by hand.
set SCRIPT_DIR=%~dp0
call "%SCRIPT_DIR%stop.cmd"
call "%SCRIPT_DIR%mvnw.cmd" -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=demo
endlocal
