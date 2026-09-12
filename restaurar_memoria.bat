@echo off
rem Devuelve al celular la memoria guardada con respaldar_memoria.bat (tras reinstalar la app).
setlocal
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "CARPETA=/sdcard/Android/data/com.pabl0sk1.jarvis/files"
cd /d "%~dp0"
if not exist datos\memoria_celular.json (
    echo No hay respaldo: primero ejecuta respaldar_memoria.bat
    exit /b 1
)

echo Conecta el celular y desbloquealo...
"%ADB%" wait-for-device
"%ADB%" shell mkdir -p %CARPETA%
"%ADB%" push datos\memoria_celular.json %CARPETA%/memoria.json
if errorlevel 1 (
    echo No se pudo copiar la memoria al celular.
    exit /b 1
)
echo Listo. Abre Jarvis: la importara al arrancar si todavia no tiene memoria propia.
