@echo off
echo 正在测试MySQL数据库连接...
echo.
echo 数据库配置信息:
echo URL: jdbc:mysql://127.0.0.1:3306/Uniview
echo Username: sa
echo Password: 11111111
echo Driver: com.mysql.jdbc.Driver
echo.
echo 正在启动应用...
cd /d D:\ai-workspace\mtp\qx-inspection-tool
java --enable-preview -jar target\qx-inspection-tool-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
pause
