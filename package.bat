@echo off
rem ============================================================
rem  QX Inspection Tool - one-click package:
rem  mvn clean package (with tests) then assemble dist\mstp-inspect\
rem
rem  ENCODING RULE: THIS FILE MUST BE PURE ASCII + CRLF.
rem  On Chinese Windows, cmd reads batch files as GBK/DBCS. UTF-8 Chinese
rem  (even inside rem comments) corrupts line parsing at data-dependent
rem  offsets, and "chcp 65001" triggers cmd's own line-offset bug.
rem  Chinese documentation lives in deploy\windows\README.md (UTF-8).
rem
rem  The dist folder is self-contained: copy it to the target Windows
rem  machine (JDK 17+) and run start.bat.
rem ============================================================
cd /d "%~dp0"

set "MVN=mvn"
where mvn >nul 2>&1 || set "MVN=D:\dev-env\apache-maven-3.9.11\bin\mvn.cmd"

echo [1/2] Maven package (with tests)...
rem call is required: mvn is a .cmd, without call the assembly steps below
rem would never run. Do not quote the bare command name (call "mvn" fails).
call %MVN% clean package
if errorlevel 1 (
    echo [ERROR] Build failed, no dist generated
    pause
    exit /b 1
)

set "DIST=dist\mstp-inspect"
echo [2/2] Assembling %DIST%\ ...
if exist "%DIST%" rmdir /s /q "%DIST%"
mkdir "%DIST%\config"
mkdir "%DIST%\data"
mkdir "%DIST%\logs"

set "JAR="
for %%f in ("target\mstp-inspect-*.jar") do if exist "%%f" set "JAR=%%f"
if not defined JAR (
    echo [ERROR] jar not found in target\
    pause
    exit /b 1
)
copy /y "%JAR%" "%DIST%\" >nul
copy /y deploy\windows\start.bat "%DIST%\" >nul
copy /y deploy\windows\stop.bat "%DIST%\" >nul
copy /y deploy\windows\stop.ps1 "%DIST%\" >nul
copy /y deploy\windows\config\application.yml "%DIST%\config\" >nul
copy /y deploy\windows\README.md "%DIST%\" >nul

echo.
echo [OK] Dist ready: %DIST%\
echo     1. Copy the whole folder to the target machine
echo     2. Edit config\application.yml (MySQL password etc.)
echo     3. Double-click start.bat to run, stop.bat to stop
echo     4. See README.md inside for the deployment guide (Chinese)
pause
