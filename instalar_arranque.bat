@echo off
rem Instala el arranque automatico de Jarvis (tarea programada de Windows, sin ventana).
rem Los detalles estan en instalar_arranque.ps1. (Sin tildes a proposito: cmd lee mal los .bat con ellas.)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar_arranque.ps1"
