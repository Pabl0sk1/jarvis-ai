# Registra la tarea programada "Jarvis": arranca el servidor de Jarvis (sin ventana) al iniciar sesion
# y lo vuelve a lanzar cada 5 minutos si se ha cerrado. La app del celular lo necesita para controlar
# la notebook; ademas vigila los cortes de luz y hace la copia de seguridad diaria.
# Lo que muestra el servidor queda en datos\jarvis.log.
# (Sin tildes a proposito: PowerShell 5 lee mal los .ps1 con caracteres especiales.)
$ErrorActionPreference = "Stop"
$proyecto = $PSScriptRoot
$python = Join-Path $proyecto ".venv\Scripts\pythonw.exe"

$accion = New-ScheduledTaskAction -Execute $python -Argument "-m jarvis --servidor" -WorkingDirectory $proyecto
$alIniciarSesion = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$cadaCincoMinutos = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
$ajustes = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName "Jarvis" -Action $accion -Trigger @($alIniciarSesion, $cadaCincoMinutos) `
    -Settings $ajustes -Description "Servidor de Jarvis para la app del celular" -Force | Out-Null

# El acceso directo antiguo de la carpeta Inicio (con ventana) ya no hace falta
$acceso = Join-Path ([Environment]::GetFolderPath("Startup")) "Jarvis.lnk"
if (Test-Path $acceso) { Remove-Item $acceso }

Start-ScheduledTask -TaskName "Jarvis"
Write-Host "Listo: Jarvis arranca solo al iniciar sesion y se relanza cada 5 minutos si se cierra."
Write-Host "Para quitarlo: quitar_arranque.bat"
