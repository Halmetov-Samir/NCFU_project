@echo off
chcp 65001 >nul
title MD5 Server
color 0B

echo ============================================
echo   MD5 Distributed Bruteforce - Server
echo ============================================
echo.

java -version >nul 2>&1
if errorlevel 1 (
    echo ОШИБКА: Java не найдена!
    echo Установи Java 17: https://adoptium.net/temurin/releases/?version=17
    pause
    exit /b 1
)

if not exist "md5-bruteforce-1.0.0.jar" (
    echo ОШИБКА: md5-bruteforce-1.0.0.jar не найден в этой папке!
    pause
    exit /b 1
)

echo Java найдена ✓
echo Jar найден ✓
echo.
echo Сервер запускается на порту 8080...
echo Открой http://localhost:8080/ в браузере
echo.
echo НЕ ЗАКРЫВАЙ это окно! Ctrl+C для остановки.
echo ============================================
echo.

java -jar md5-bruteforce-1.0.0.jar server

echo.
echo ============================================
echo   Сервер остановлен.
echo ============================================
pause