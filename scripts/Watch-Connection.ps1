#Requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
$published=''
while($true) {
    try {
        $state=Get-Content .local/backend-tunnel.json -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json
        $process=if($state){Get-Process -Id $state.id -ErrorAction SilentlyContinue}else{$null}
        if(-not $process -or $process.StartTime.ToUniversalTime().Ticks-ne ([datetime]$state.started).ToUniversalTime().Ticks) {
            & (Join-Path $PSScriptRoot 'Stop-BackendTunnel.ps1') -KeepWatcher
            & (Join-Path $PSScriptRoot 'Start-BackendTunnel.ps1')
        }
        $text=Get-Content .local/backend-tunnel.out.log -Raw
        if(-not [string]::IsNullOrEmpty($text)) {
            $urls=[regex]::Matches($text,'tunneled with tls termination,\s+(https://[a-z0-9.-]+)')
            if($urls.Count-gt 0) {
                $url=$urls[$urls.Count-1].Groups[1].Value
                if($url-ne $published -and (Invoke-RestMethod "$url/health" -TimeoutSec 5).stage-eq 'backend') {
                    $url|Set-Content .local/backend-public-url.txt
                    & (Join-Path $PSScriptRoot 'Publish-Connection.ps1') -Url $url
                    $published=$url
                }
            }
        }
    } catch { Write-Output 'Connection update failed; retrying shortly. Details and credentials are not logged.' }
    Start-Sleep -Seconds 20
}
