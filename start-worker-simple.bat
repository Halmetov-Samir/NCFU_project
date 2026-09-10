@echo off
chcp 65001 >nul
title MD5 Worker
color 0A

echo ============================================
echo   MD5 Distributed Bruteforce - Worker
echo ============================================
echo.

REM ============ НАСТРОЙКИ ============
set SERVER_URL=https://unvarying-cofounder-nutshell.ngrok-free.dev
set WORKER_ID=worker-friend-1
REM ===================================

if not exist "target\md5-bruteforce-1.0.0.jar" (
    echo ОШИБКА: target\md5-bruteforce-1.0.0.jar не найден!
    echo Этот файл должен лежать РЯДОМ с этим bat.
    echo Скачай его заново.
    pause
    exit /b 1
)

echo [1/3] Проверка Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo ОШИБКА: Java не найдена!
    echo Установи Java 17: https://adoptium.net/temurin/releases/?version=17
    pause
    exit /b 1
)
echo      Java найдена ✓

echo [2/3] Проверка jar-файла...
echo      Jar найден ✓

echo [3/3] Подключение к серверу...
echo      Server URL: %SERVER_URL%
echo      Worker ID:  %WORKER_ID%
echo.
echo НЕ ЗАКРЫВАЙ это окно! Ctrl+C для остановки.
echo.

set SERVER_URL=%SERVER_URL%
set WORKER_ID=%WORKER_ID%
java -jar target\md5-bruteforce-1.0.0.jar worker

pause