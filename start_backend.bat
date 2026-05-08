@echo off
setlocal enabledelayedexpansion

echo ============================================
echo GastroPOS Relay - Start Local FastAPI Backend
echo ============================================
echo.

set "SCRIPT_DIR=%~dp0"
set "BACKEND_DIR=%SCRIPT_DIR%backend"

if not exist "%BACKEND_DIR%" (
    set "BACKEND_DIR=%SCRIPT_DIR%..\backend"
)

if not exist "%BACKEND_DIR%\requirements.txt" (
    echo Could not auto-find backend folder.
    echo.
    set /p BACKEND_DIR=Enter full backend folder path ^(contains requirements.txt^): 
)

if not exist "%BACKEND_DIR%\requirements.txt" (
    echo.
    echo ERROR: requirements.txt not found in "%BACKEND_DIR%"
    echo Please provide the correct backend path and run again.
    pause
    exit /b 1
)

if not exist "%BACKEND_DIR%\main.py" (
    echo.
    echo WARNING: main.py not found in "%BACKEND_DIR%".
    echo Uvicorn may fail unless module path is adjusted.
)

echo.
echo Using backend directory:
echo %BACKEND_DIR%
echo.

cd /d "%BACKEND_DIR%"

if not exist ".venv\Scripts\python.exe" (
    echo Creating virtual environment...
    py -3 -m venv .venv
    if errorlevel 1 (
        echo ERROR: Failed to create venv. Ensure Python launcher ^(py^) is installed.
        pause
        exit /b 1
    )
)

echo Installing dependencies...
call ".venv\Scripts\python.exe" -m pip install --upgrade pip
call ".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 (
    echo ERROR: Failed to install requirements.
    pause
    exit /b 1
)

echo.
echo Starting backend at http://0.0.0.0:8765
echo Health check URL from phone:
echo http://%COMPUTERNAME%:8765/health ^(or use LAN IP^)
echo.

call ".venv\Scripts\python.exe" -m uvicorn main:app --host 0.0.0.0 --port 8765

echo.
echo Backend stopped.
pause
