#Requires -Version 7.0
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
New-Item -ItemType Directory -Force .local | Out-Null
function Start-ConnectionWatcher {
    $state=Join-Path $projectRoot '.local/connection-watch.json'
    if(Test-Path $state) {
        $saved=Get-Content $state -Raw|ConvertFrom-Json
        $existing=Get-Process -Id $saved.id -ErrorAction SilentlyContinue
        if($existing -and $existing.StartTime.ToUniversalTime().Ticks-eq ([datetime]$saved.started).ToUniversalTime().Ticks){return}
    }
    $script=Join-Path $PSScriptRoot 'Watch-Connection.ps1'
    $process=Start-Process (Get-Command pwsh).Source -ArgumentList @('-NoProfile','-File',"`"$script`"") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput .local/connection-watch.out.log -RedirectStandardError .local/connection-watch.err.log
    @{id=$process.Id;started=$process.StartTime.ToUniversalTime().ToString('o')}|ConvertTo-Json|Set-Content $state
}
function Test-Backend {
    try { (Invoke-RestMethod http://127.0.0.1:8081/health -TimeoutSec 2 -NoProxy).stage -eq 'backend' } catch { $false }
}
if(-not (Test-Backend)) {
    $script=Join-Path $PSScriptRoot 'Start-Backend.ps1'
    Start-Process (Get-Command pwsh).Source -ArgumentList @('-NoProfile','-File',"`"$script`"") -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput .local/internet-server.out.log -RedirectStandardError .local/internet-server.err.log | Out-Null
    $deadline=(Get-Date).AddSeconds(90)
    while(-not (Test-Backend)) {
        if((Get-Date)-gt $deadline){throw 'Backend startup timed out. See .local/internet-server.*.log'}
        Start-Sleep -Seconds 2
    }
}
$existing=Join-Path $projectRoot '.local/backend-public-url.txt'
$state=Join-Path $projectRoot '.local/backend-tunnel.json'
if((Test-Path $existing) -and (Test-Path $state)) {
    $saved=Get-Content $state -Raw | ConvertFrom-Json
    $process=Get-Process -Id $saved.id -ErrorAction SilentlyContinue
    if($process -and $process.StartTime.ToUniversalTime().Ticks-eq ([datetime]$saved.started).ToUniversalTime().Ticks) {
        $url=(Get-Content $existing -Raw).Trim()
        try { $ready=(Invoke-RestMethod "$url/health" -TimeoutSec 5).stage-eq 'backend' } catch { $ready=$false }
        if($ready){Write-Output "Android server: $url";& (Join-Path $PSScriptRoot 'Publish-Connection.ps1') -Url $url;Start-ConnectionWatcher;return}
    }
}
& (Join-Path $PSScriptRoot 'Stop-BackendTunnel.ps1') -KeepWatcher
& (Join-Path $PSScriptRoot 'Start-BackendTunnel.ps1')
& (Join-Path $PSScriptRoot 'Publish-Connection.ps1') -Url ((Get-Content $existing -Raw).Trim())
Start-ConnectionWatcher
