@echo off
echo 正在检查MySQL容器内的用户权限...
echo.
docker exec mysql-uniview mysql -u root -proot123 -D mysql -e "SELECT user, host, authentication_string FROM user WHERE user IN ('root', 'sa') OR host IN ('%%', '172.17.%%', '127.0.0.1', 'localhost')"
echo.
echo.
echo 如果看到sa用户的host是localhost，说明sa用户只能从容器内部连接
echo 需要创建允许外部连接的sa用户
pause
