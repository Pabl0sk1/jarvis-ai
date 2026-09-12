@echo off
chcp 65001 >nul
rem Deja de arrancar Jarvis al iniciar Windows.
set "ACCESO=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\Jarvis.lnk"
if exist "%ACCESO%" (
    del "%ACCESO%"
    echo Listo: Jarvis ya no arrancará solo con Windows.
) else (
    echo Jarvis no estaba en el arranque de Windows.
)
