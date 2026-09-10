@echo off
chcp 65001 >nul
title MD5 ngrok Tunnel
color 0E

if not exist "C:\ngrok\ngrok.exe" (
    echo ОШИБКА: ngrok.exe не найден в C:\ngrok\
    pause
    exit /b 1
)

echo ============================================
echo   ngrok Tunnel для MD5 Server
echo ============================================
echo.
echo Запуск туннеля на порт 8080...
echo.
echo СКОПИРУЙ ссылку "Forwarding" и отправь другу!
echo НЕ ЗАКРЫВАЙ это окно!
echo.

C:\ngrok\ngrok.exe http 8080

pause