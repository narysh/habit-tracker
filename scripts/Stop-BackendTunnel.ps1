#Requires -Version 7.0
param([switch]$KeepWatcher)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(-not $KeepWatcher) {
    $watch=Join-Path $projectRoot '.local/connection-watch.json'
    if(Test-Path $watch) {
        $saved=Get-Content $watch -Raw|ConvertFrom-Json
        $process=Get-Process -Id $saved.id -ErrorAction SilentlyContinue
        if($process -and $process.StartTime.ToUniversalTime().Ticks-eq ([datetime]$saved.started).ToUniversalTime().Ticks){Stop-Process -Id $saved.id}
        Remove-Item -LiteralPath $watch
    }
}
$path=Join-Path $projectRoot '.local/backend-tunnel.json'
if(Test-Path -LiteralPath $path){
    $state=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    $process=Get-Process -Id $state.id -ErrorAction SilentlyContinue
    if($process -and $process.StartTime.ToUniversalTime().Ticks-eq ([datetime]$state.started).ToUniversalTime().Ticks){Stop-Process -Id $state.id}
    Remove-Item -LiteralPath $path
}
