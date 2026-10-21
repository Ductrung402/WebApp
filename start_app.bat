@echo off
title Khoi dong Web App EZam
echo ========================================================
echo   DANG KHOI DONG HE THONG EZAM - AI QUIZ ^& EXAM MANAGEMENT
echo ========================================================
echo Java 17 / Tomcat 9 / React
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_local.ps1"
if errorlevel 1 pause

