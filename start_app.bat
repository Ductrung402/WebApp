@echo off
title Khoi dong Web App Quiz AI
echo ========================================================
echo   DANG KHOI DONG HE THONG QUIZ AI & EXAM MANAGEMENT
echo ========================================================
echo [1/3] Khoi chay Backend FastAPI (Port 8000)...
start "Backend - FastAPI (Port 8000)" cmd /k "cd /d %~dp0backend && ..\.venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000"

echo [2/3] Khoi chay Frontend Vite React (Port 5173)...
start "Frontend - React Vite (Port 5173)" cmd /k "cd /d %~dp0frontend && npm.cmd run dev"

echo [3/3] Dang mo trinh duyet toi http://localhost:5173...
timeout /t 3 /nobreak >nul
start http://localhost:5173

echo.
echo ========================================================
echo   HE THONG DA DUOC KHOI CHAY THANH CONG!
echo   Frontend: http://localhost:5173
echo   Backend API Docs: http://localhost:8000/docs
echo ========================================================

