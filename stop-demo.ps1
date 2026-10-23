$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskJar = Join-Path $taskRoot 'target\tuya-local-demo-0.1.0.jar'
$taskPidFile = Join-Path $taskRoot '.runtime\app.pid'
if (-not (Test-Path -LiteralPath $taskPidFile)) { Write-Output 'Нет сохранённого процесса. Для запуска через Maven используйте Ctrl+C в его терминале.'; exit 0 }
$taskProcessId = [int](Get-Content -LiteralPath $taskPidFile -Raw)
$taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$taskProcessId" -ErrorAction SilentlyContinue
if ($taskProcess -and $taskProcess.CommandLine.Contains($taskJar)) {
    Stop-Process -Id $taskProcessId -ErrorAction Stop
    Write-Output 'Приложение остановлено.'
} else { Write-Output 'Сохранённый процесс уже завершился; другие процессы не затронуты.' }
Remove-Item -LiteralPath $taskPidFile -ErrorAction SilentlyContinue
