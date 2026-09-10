@echo off
chcp 65001 >nul
title MD5 Worker
color 0A

echo ============================================
echo   MD5 Distributed Bruteforce - Worker
echo ============================================
echo.

REM ================================================
REM   НАСТРОЙКИ (уже заполнены — менять не нужно)
REM ================================================
set SERVER_URL=https://unvarying-cofounder-nutshell.ngrok-free.dev
set WORKER_ID=worker-friend-1
REM ================================================

echo [1/3] Проверка Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo.
    echo ОШИБКА: Java не найдена!
    echo.
    echo Установи Java 17: https://adoptium.net/temurin/releases/?version=17
    echo После установки запусти этот файл снова.
    echo.
    pause
    exit /b 1
)
echo      Java найдена ✓
echo.

echo [2/3] Проверка jar-файла...
if not exist "md5-bruteforce-1.0.0.jar" (
    echo.
    echo ОШИБКА: файл md5-bruteforce-1.0.0.jar не найден!
    echo Положи его рядом с этим bat-файлом.
    echo.
    pause
    exit /b 1
)
echo      Jar найден ✓
echo.

echo [3/3] Подключение к серверу...
echo      Server URL: %SERVER_URL%
echo      Worker ID:  %WORKER_ID%
echo.
echo ============================================
echo   Воркер запущен. НЕ ЗАКРЫВАЙ это окно!
echo   Для остановки нажми Ctrl+C
echo ============================================
echo.

set SERVER_URL=%SERVER_URL%
set WORKER_ID=%WORKER_ID%
java -jar md5-bruteforce-1.0.0.jar worker

echo.
echo ============================================
echo   Воркер остановлен.
echo ============================================
pause