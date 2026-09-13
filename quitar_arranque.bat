@echo off
rem Quita el arranque automatico de Jarvis y detiene el servidor. Detalles en quitar_arranque.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0quitar_arranque.ps1"
