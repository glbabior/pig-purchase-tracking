@echo off
setlocal
rem Generate the API documentation from the source comments and open it.
rem
rem This exists because "run mvnw javadoc:javadoc, then open a file several levels down
rem inside target" is not something anyone discovers. The reasoning behind the mapping
rem passes, the spend rules and the privacy boundary lives in class and method comments
rem rather than in a separate design document, so the generated site is worth reading --
rem see docs/ARCHITECTURE.md for the design view instead.
rem
rem The output path is the plugin's to choose, not ours: it has already moved once
rem (target/site/apidocs -> target/reports/apidocs), so this looks for the index rather
rem than hardcoding one path and breaking on the next upgrade.
rem
rem Output is a build artifact: gitignored, regenerated on demand, never committed.
set SCRIPT_DIR=%~dp0
call "%SCRIPT_DIR%mvnw.cmd" -q javadoc:javadoc
if errorlevel 1 (
  echo.
  echo Javadoc generation failed - nothing was opened. A malformed comment is the usual
  echo cause: doclint checks HTML and syntax, so an unescaped ^< or ^& in a comment is an
  echo error. The message above names the file and line.
  exit /b 1
)

set DOCS=
for %%D in (
  "%SCRIPT_DIR%target\reports\apidocs\index.html"
  "%SCRIPT_DIR%target\site\apidocs\index.html"
  "%SCRIPT_DIR%target\apidocs\index.html"
) do if not defined DOCS if exist %%D set DOCS=%%D

if not defined DOCS (
  echo.
  echo Javadoc reported success but no index.html was found under target. The plugin's
  echo output directory has probably changed again - look for index.html under target
  echo and add that path to the list in this script.
  exit /b 1
)

echo Opening %DOCS%
start "" %DOCS%
endlocal
