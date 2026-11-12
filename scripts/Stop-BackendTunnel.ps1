#Requires -Version 7.0
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$path=Join-Path $projectRoot '.local/backend-tunnel.json'
if(Test-Path -LiteralPath $path){
    $state=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    $process=Get-Process -Id $state.id -ErrorAction SilentlyContinue
    if($process -and $process.StartTime.ToUniversalTime().Ticks-eq ([datetime]$state.started).ToUniversalTime().Ticks){Stop-Process -Id $state.id}
    Remove-Item -LiteralPath $path
}
