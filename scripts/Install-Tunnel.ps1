#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolDir = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force $toolDir | Out-Null
$target = Join-Path $toolDir 'cloudflared.exe'
$expected = '2493dbc168923bb3e59e5e7716f71deced92992eea9acd6e9d74f84ac7751343'
if (-not (Test-Path -LiteralPath $target)) {
    Invoke-WebRequest 'https://github.com/cloudflare/cloudflared/releases/download/2026.10.7/cloudflared-windows-amd64.exe' -OutFile $target
}
if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'cloudflared SHA-256 mismatch. Do not execute the downloaded file.'
}
& $target --version
