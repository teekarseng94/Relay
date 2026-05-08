@echo off
setlocal enabledelayedexpansion

echo ============================================
echo GastroPOS Relay - LAN IP + Backend Starter
echo ============================================
echo.

echo Detecting local IPv4 addresses...
for /f "tokens=2 delims=:" %%A in ('ipconfig ^| findstr /R /C:"IPv4 Address"') do (
    set "IP=%%A"
    set "IP=!IP: =!"
    if not "!IP!"=="127.0.0.1" (
        echo - !IP!
    )
)
echo.

echo Expected app URL format:
echo http://^<YOUR-PC-LAN-IP^>:8765/api/v1/external-orders
echo.
echo Example for your current setup:
echo http://192.168.5.32:8765/api/v1/external-orders
echo.
echo Health check from phone browser:
echo http://192.168.5.32:8765/health
echo.

set "SCRIPT_DIR=%~dp0"
set "START_SCRIPT=%SCRIPT_DIR%start_backend.bat"

if not exist "%START_SCRIPT%" (
    echo ERROR: start_backend.bat not found at:
    echo %START_SCRIPT%
    pause
    exit /b 1
)

echo Press any key to start backend...
pause >nul
call "%START_SCRIPT%"
