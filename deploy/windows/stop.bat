@echo off
rem QX Inspection Tool - stop script (kills java by jar name, see stop.ps1)
rem ENCODING RULE: THIS FILE MUST BE PURE ASCII + CRLF. Chinese only in stop.ps1 (UTF-8 with BOM).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
pause
