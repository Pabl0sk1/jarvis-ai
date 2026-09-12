@echo off
chcp 65001 >nul
rem Copia la memoria de Jarvis del celular al PC: datos\memoria_celular.json
setlocal
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
cd /d "%~dp0"
if not exist datos mkdir datos

echo Conecta el celular y desbloquéalo...
"%ADB%" wait-for-device
"%ADB%" pull /sdcard/Android/data/com.pabl0sk1.jarvis/files/memoria.json datos\memoria_celular.json
if errorlevel 1 (
    echo No encontré la memoria en el celular: ¿Jarvis ya ha guardado algún recuerdo?
    exit /b 1
)
echo Memoria guardada en datos\memoria_celular.json
