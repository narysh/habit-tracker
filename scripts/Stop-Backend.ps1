#Requires -Version 7.0
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$dataDir=Join-Path $projectRoot '.local/postgres'
if(-not ([IO.Path]::GetFullPath($dataDir).StartsWith([IO.Path]::GetFullPath($projectRoot)+[IO.Path]::DirectorySeparatorChar))){throw 'Unexpected data directory'}
$pidFile=Join-Path $projectRoot '.local/backend.pid'
if(Test-Path -LiteralPath $pidFile) {
    $serverId=[int](Get-Content -LiteralPath $pidFile -Raw)
    $server=Get-CimInstance Win32_Process -Filter "ProcessId = $serverId"
    if($server -and $server.CommandLine -like '*dev.habittracker.backend.LocalDevServerKt*'){Stop-Process -Id $serverId}
    Remove-Item -LiteralPath $pidFile
}
$postgresPid=Join-Path $dataDir 'postmaster.pid'
if(Test-Path -LiteralPath $postgresPid) {
    $databaseId=[int](Get-Content -LiteralPath $postgresPid -TotalCount 1)
    $database=Get-CimInstance Win32_Process -Filter "ProcessId = $databaseId"
    if($database -and $database.Name-eq 'postgres.exe' -and $database.CommandLine.Replace('/','\').Contains($dataDir.Replace('/','\'))) {
        $control=Join-Path (Split-Path $database.ExecutablePath -Parent) 'pg_ctl.exe'
        & $control stop -D $dataDir -m fast -w -t 15
        if($LASTEXITCODE-ne 0){throw 'PostgreSQL did not stop cleanly'}
    }
}
