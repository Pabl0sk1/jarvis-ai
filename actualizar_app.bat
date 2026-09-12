@echo off
chcp 65001 >nul
rem Compila la app de Jarvis y la instala en el celular SIN borrar sus datos.
setlocal
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
cd /d "%~dp0android"

echo Compilando la app...
call gradlew.bat assembleRelease --console=plain -q
if errorlevel 1 (
    echo La compilación falló.
    exit /b 1
)
rem Libera la memoria que Gradle deja ocupada
call gradlew.bat --stop >nul 2>&1

echo.
echo Conecta el celular (USB o depuración inalámbrica), desbloquéalo y acepta los avisos...
"%ADB%" wait-for-device
echo Instalando: acepta en el celular el aviso de Xiaomi "Instalar vía USB".
"%ADB%" install -r app\build\outputs\apk\release\app-release.apk
if errorlevel 1 (
    echo No se pudo instalar. Si dice INSTALL_FAILED_UPDATE_INCOMPATIBLE, la app instalada
    echo está firmada con otra clave: respalda la memoria, desinstálala y vuelve a ejecutar esto.
    exit /b 1
)
echo.
echo Listo: Jarvis actualizado conservando sus datos.
