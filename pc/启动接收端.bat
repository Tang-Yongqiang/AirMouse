@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在启动 AirMouse 接收端（关掉这个窗口就断开连接）...
echo.
python -u airmouse_server.py %*
echo.
echo 服务端已退出。
pause
