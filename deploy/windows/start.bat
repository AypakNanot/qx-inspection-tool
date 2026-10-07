@echo off
rem ============================================================
rem  QX Inspection Tool - Windows start script
rem  1. Working dir is fixed to the script dir (SQLite ./data/qx_inspection.db
rem     is relative to cwd, so starting elsewhere creates an empty DB)
rem  2. Spring Boot auto-loads config\application.yml next to this script
rem     and overrides the values baked into the jar
rem  3. Runs in its own window; closing that window stops the app.
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

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] java not found. Install JDK 17+ and add it to PATH.
    pause
    exit /b 1
)

if not exist data mkdir data
if not exist logs mkdir logs
if not exist config\application.yml (
    echo [WARN] config\application.yml not found, using built-in defaults
)

echo [INFO] Starting %JAR%
echo [INFO] http://localhost:38543/
start "QX-Inspection" java -jar "%JAR%"
rem wait ~5s so this window shows the INFO lines before closing
ping -n 6 127.0.0.1 >nul
