#Requires -Version 7.0
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
New-Item -ItemType Directory -Force .local | Out-Null
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
        try { if((Invoke-RestMethod "$url/health" -TimeoutSec 5).stage-eq 'backend'){Write-Output "Android server: $url"; return} } catch { }
    }
}
& (Join-Path $PSScriptRoot 'Stop-BackendTunnel.ps1')
& (Join-Path $PSScriptRoot 'Start-BackendTunnel.ps1')
