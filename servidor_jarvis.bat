@echo off
rem Mantiene en marcha el servidor de Jarvis para la app del celular: si se cierra o falla,
rem lo vuelve a arrancar a los 10 segundos. Es lo que lanza el arranque automatico de Windows.
rem (Sin tildes a proposito: cmd lee mal los .bat con caracteres especiales.)
title Jarvis (servidor)
cd /d "%~dp0"
:bucle
".venv\Scripts\python.exe" -m jarvis --servidor
echo El servidor de Jarvis se detuvo (codigo %errorlevel%). Lo reinicio en 10 segundos...
timeout /t 10 /nobreak >nul
goto bucle
