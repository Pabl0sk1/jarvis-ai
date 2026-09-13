# Quita la tarea programada "Jarvis" y detiene el servidor que se este ejecutando.
# (Sin tildes a proposito: PowerShell 5 lee mal los .ps1 con caracteres especiales.)
Unregister-ScheduledTask -TaskName "Jarvis" -Confirm:$false -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match 'jarvis --servidor' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
$acceso = Join-Path ([Environment]::GetFolderPath("Startup")) "Jarvis.lnk"
if (Test-Path $acceso) { Remove-Item $acceso }
Write-Host "Listo: Jarvis ya no arranca solo con Windows."
