$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateFile = Join-Path $projectRoot '.local\processes.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Output 'No managed probe processes.'; return }
foreach ($record in @(Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json)) {
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$record.started).ToUniversalTime().Ticks) {
        Stop-Process -Id $record.id
        $process.WaitForExit(5000) | Out-Null
        Write-Output "Stopped $($record.role)."
    }
}
Remove-Item -LiteralPath $stateFile
