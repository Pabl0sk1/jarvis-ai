@echo off
rem Hace que Jarvis arranque solo al iniciar Windows, en modo servidor y vigilado (si se cierra, se
rem reinicia): la app del celular puede controlar la notebook, se vigilan los cortes de luz y se hace
rem la copia de seguridad diaria. (Sin tildes a proposito: cmd lee mal los .bat con caracteres especiales.)
setlocal
set "INICIO=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
powershell -NoProfile -Command "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('%INICIO%\Jarvis.lnk'); $s.TargetPath = '%~dp0servidor_jarvis.bat'; $s.Arguments = ''; $s.WorkingDirectory = '%~dp0'; $s.WindowStyle = 7; $s.Save()"
if errorlevel 1 (
    echo No se pudo crear el acceso directo.
    exit /b 1
)
echo Listo: Jarvis arrancara solo al iniciar Windows. Para quitarlo: quitar_arranque.bat
