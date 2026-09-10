@echo off
chcp 65001 >nul
title MD5 Worker
color 0A

echo ============================================
echo   MD5 Distributed Bruteforce - Worker
echo ============================================
echo.

REM === Проверка Java ===
java -version >nul 2>&1
if errorlevel 1 (
    echo ОШИБКА: Java не найдена!
    echo Установи Java 17: https://adoptium.net/temurin/releases/?version=17
    pause
    exit /b 1
)

REM === Проверка jar ===
if not exist "md5-bruteforce-1.0.0.jar" (
    echo ОШИБКА: md5-bruteforce-1.0.0.jar не найден!
    pause
    exit /b 1
)

REM === Запрос параметров ===
set SERVER_URL=http://localhost:8080
set /p INPUT_URL="Адрес сервера [Enter = %SERVER_URL%]: "
if not "%INPUT_URL%"=="" set SERVER_URL=%INPUT_URL%

set WORKER_ID=worker-%RANDOM%
set /p INPUT_ID="ID воркера [Enter = %WORKER_ID%]: "
if not "%INPUT_ID%"=="" set WORKER_ID=%INPUT_ID%

echo.
echo ============================================
echo   Запуск воркера
echo   Server URL: %SERVER_URL%
echo   Worker ID:  %WORKER_ID%
echo ============================================
echo.
echo НЕ ЗАКРЫВАЙ это окно! Ctrl+C для остановки.
echo.

set SERVER_URL=%SERVER_URL%
set WORKER_ID=%WORKER_ID%
java -jar md5-bruteforce-1.0.0.jar worker

echo.
echo ============================================
echo   Воркер остановлен.
echo ============================================
pause