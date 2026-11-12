#Requires -Version 7.0
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$state=Join-Path $projectRoot '.local/backend-tunnel.json'
if(Test-Path -LiteralPath $state){throw 'Stop the existing backend tunnel first (scripts/Stop-BackendTunnel.ps1)'}
$out=Join-Path $projectRoot '.local/backend-tunnel.out.log';$err=Join-Path $projectRoot '.local/backend-tunnel.err.log'
$known=Join-Path $projectRoot '.local/ssh_known_hosts'
$arguments=@('-T','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','IdentityAgent=none','-o','IdentityFile=none','-o','StrictHostKeyChecking=accept-new','-o',"`"UserKnownHostsFile=$known`"",'-o','ConnectTimeout=10','-o','ServerAliveInterval=30','-o','ExitOnForwardFailure=yes','-R','80:127.0.0.1:8081','nokey@localhost.run')
$process=Start-Process (Get-Command ssh).Source -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
@{id=$process.Id;started=$process.StartTime.ToUniversalTime().ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath $state
$deadline=(Get-Date).AddSeconds(30)
do {
    Start-Sleep -Milliseconds 250
    $text=Get-Content -LiteralPath $out -Raw -ErrorAction SilentlyContinue
    if($text-match 'tunneled with tls termination,\s+(https://[a-z0-9.-]+)'){
        $url=$Matches[1]
        $health=Invoke-RestMethod "$url/health" -TimeoutSec 10
        if($health.stage-ne 'backend'){throw 'Unexpected service at public endpoint'}
        $url|Set-Content .local/backend-public-url.txt
        Write-Output "Temporary Android API URL: $url"
        Write-Output 'The domain may rotate. This does not change the local desktop OAuth connection.'
        return
    }
    $process.Refresh()
}while(-not $process.HasExited -and (Get-Date)-lt $deadline)
throw 'Tunnel did not return a working URL; see .local/backend-tunnel.err.log'
