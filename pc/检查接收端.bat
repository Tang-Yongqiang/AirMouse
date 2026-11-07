@echo off
chcp 65001 >nul
title AirMouse 接收端状态
cd /d "%~dp0"

echo ============================================================
echo  AirMouse PC 接收端 · 自检
echo ============================================================
echo.

echo [1] 进程
tasklist /FI "IMAGENAME eq pythonw.exe" /NH 2>nul | findstr /I pythonw >nul
if errorlevel 1 (
  echo     没有在跑
) else (
  tasklist /FI "IMAGENAME eq pythonw.exe" /NH
)

echo.
echo [2] 端口 47777
netstat -ano | findstr ":47777" | findstr "LISTENING"
if errorlevel 1 echo     没有在监听

echo.
echo [3] 日志（最后 6 行）
set "LOG=%LOCALAPPDATA%\AirMouse\server.log"
if exist "%LOG%" (
  powershell -NoProfile -Command "Get-Content -LiteralPath $env:LOG -Tail 6 -Encoding UTF8"
) else (
  echo     日志文件还不存在（从没启动过）
)

echo.
echo [4] 本机 IP（手机端要填这个）
powershell -NoProfile -Command "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } | Select-Object -ExpandProperty IPAddress"

echo.
echo ------------------------------------------------------------
echo  没在跑？直接双击「启动接收端.bat」或本目录的启动快捷方式。
echo  开机自启：启动文件夹里的「AirMouse 接收端.lnk」
echo ------------------------------------------------------------
pause
