@echo off
rem Arranca Jarvis. Acepta los mismos argumentos que "python -m jarvis" (p. ej. --texto).
cd /d "%~dp0"
".venv\Scripts\python.exe" -m jarvis %*
