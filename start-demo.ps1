$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskJar = Join-Path $taskRoot 'target\tuya-local-demo-0.1.0.jar'
$taskRuntime = Join-Path $taskRoot '.runtime'
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Сначала выполните mvn package в папке проекта.' }
New-Item -ItemType Directory -Force -Path $taskRuntime | Out-Null
$taskPidFile = Join-Path $taskRuntime 'app.pid'
if (Test-Path -LiteralPath $taskPidFile) {
    $taskPriorId = [int](Get-Content -LiteralPath $taskPidFile -Raw)
    $taskPrior = Get-CimInstance Win32_Process -Filter "ProcessId=$taskPriorId" -ErrorAction SilentlyContinue
    if ($taskPrior -and $taskPrior.CommandLine.Contains($taskJar)) { Write-Output 'Приложение уже запущено: http://localhost:8080'; exit 0 }
}
$taskJava = (Get-Command java -ErrorAction Stop).Source
$taskProcess = Start-Process -FilePath $taskJava -ArgumentList @('-jar', ('"' + $taskJar + '"')) -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRuntime 'app.log') -RedirectStandardError (Join-Path $taskRuntime 'error.log') -PassThru
[System.IO.File]::WriteAllText($taskPidFile, [string]$taskProcess.Id)
Write-Output 'Запуск: http://localhost:8080. Остановка: .\stop-demo.ps1'
