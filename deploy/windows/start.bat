@echo off
rem ============================================================
rem  QX Inspection Tool - Windows start script
rem  1. Working dir is fixed to the script dir (SQLite ./data/qx_inspection.db
rem     is relative to cwd, so starting elsewhere creates an empty DB)
rem  2. Spring Boot auto-loads config\application.yml next to this script
rem     and overrides the values baked into the jar
rem  3. Java: prefers the bundled jre\bin\java.exe (JRE 17) so a customer
rem     machine with only JDK 8 keeps working untouched - no install,
rem     no uninstall, no JAVA_HOME/PATH change. Falls back to java on PATH.
rem  4. Runs in its own window; closing that window stops the app.
rem     For production use an NSSM service instead - see README.md
rem
rem  ENCODING RULE: THIS FILE MUST BE PURE ASCII + CRLF.
rem  Chinese only in README.md (UTF-8). GBK console + UTF-8 Chinese in a
rem  batch file corrupts cmd's line parsing; chcp 65001 is also buggy.
rem ============================================================
cd /d "%~dp0"

set "JAR="
for %%f in ("%~dp0mstp-inspect*.jar") do if exist "%%f" set "JAR=%%f"
if not defined JAR (
    echo [ERROR] mstp-inspect*.jar not found. Run package.bat first.
    pause
    exit /b 1
)

rem --- resolve java: bundled JRE first, PATH as fallback ---
set "JAVA=%~dp0jre\bin\java.exe"
if exist "%JAVA%" goto java_ok
set "JAVA="
for /f "delims=" %%j in ('where java 2^>nul') do if not defined JAVA set "JAVA=%%j"
if not defined JAVA (
    echo [ERROR] No Java found: jre\bin\java.exe missing and no java on PATH.
    echo         Keep the bundled jre\ folder next to this script, or install JDK 17+.
    pause
    exit /b 1
)
:java_ok

if not exist data mkdir data
if not exist logs mkdir logs
if not exist config\application.yml (
    echo [WARN] config\application.yml not found, using built-in defaults
)

echo [INFO] Java: %JAVA%
echo [INFO] Starting %JAR%
echo [INFO] http://localhost:38543/
start "QX-Inspection" "%JAVA%" -jar "%JAR%"
rem wait ~5s so this window shows the INFO lines before closing
ping -n 6 127.0.0.1 >nul
