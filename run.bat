@echo off
cd /d D:\ai-workspace\mtp\qx-inspection-tool
java --enable-preview -jar target\mstp-inspect-1.0.0.jar > startup.log 2>&1
type startup.log
