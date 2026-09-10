@echo off
chcp 65001 >nul
title MD5 Worker
color 0A

echo ============================================
echo   MD5 Distributed Bruteforce - Worker
echo ============================================
echo.

if not exist "target\md5-bruteforce-1.0.0.jar" (
    echo ОШИБКА: target\md5-bruteforce-1.0.0.jar не найден!
    echo Сначала собери проект: mvn clean package -DskipTests
    pause
    exit /b 1
)

set SERVER_URL=http://localhost:8080
set /p INPUT_URL="Адрес сервера [Enter = %SERVER_URL%]: "
if not "%INPUT_URL%"=="" set SERVER_URL=%INPUT_URL%

set WORKER_ID=worker-%RANDOM%
set /p INPUT_ID="ID воркера [Enter = %WORKER_ID%]: "
if not "%INPUT_ID%"=="" set WORKER_ID=%INPUT_ID%

echo.
echo Server URL: %SERVER_URL%
echo Worker ID:  %WORKER_ID%
echo.
echo НЕ ЗАКРЫВАЙ это окно! Ctrl+C для остановки.
echo.

set SERVER_URL=%SERVER_URL%
set WORKER_ID=%WORKER_ID%
java -jar target\md5-bruteforce-1.0.0.jar worker

pause