@echo off
chcp 65001 >nul
title MD5 ngrok Tunnel
color 0E

echo ============================================
echo   ngrok Tunnel для MD5 Server
echo ============================================
echo.

REM === Проверка ngrok ===
if not exist "C:\ngrok\ngrok.exe" (
    echo ОШИБКА: ngrok.exe не найден в C:\ngrok\
    echo.
    echo Скачай ngrok:
    echo   1. Зарегистрируйся: https://dashboard.ngrok.com/signup
    echo   2. Скачай: https://ngrok.com/download
    echo   3. Распакуй в C:\ngrok\
    echo   4. Привяжи токен:
    echo      C:\ngrok\ngrok.exe config add-authtoken ТВОЙ_ТОКЕН
    echo.
    pause
    exit /b 1
)

echo ngrok найден ✓
echo.
echo Запуск туннеля на порт 8080...
echo.
echo ============================================
echo   СКОПИРУЙ ссылку "Forwarding" и отправь другу!
echo   НЕ ЗАКРЫВАЙ это окно!
echo ============================================
echo.

C:\ngrok\ngrok.exe http 8080

echo.
echo ============================================
echo   ngrok остановлен.
echo ============================================
pause