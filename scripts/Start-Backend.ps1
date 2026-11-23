#Requires -Version 7.0
param([switch]$Docker)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
New-Item -ItemType Directory -Force .local | Out-Null
if([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) { $env:GRADLE_USER_HOME=Join-Path $projectRoot '.local/gradle-user-home' }
$config=Join-Path $projectRoot '.local/backend.env'
function New-Secret {
    [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')
}
if(-not (Test-Path -LiteralPath $config)) {
    @("DB_PASSWORD=$(New-Secret)","MCP_CLIENT_SECRET=$(New-Secret)",'PUBLIC_URL=http://127.0.0.1:8081','MCP_CLIENT_ID=habit-tracker','MCP_REDIRECT_URIS=http://127.0.0.1:45838/callback') | Set-Content -LiteralPath $config -Encoding utf8
}
foreach($line in Get-Content -LiteralPath $config) { if($line -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') } }
$social=Join-Path $projectRoot '.local/social.env'
if(Test-Path $social){foreach($line in Get-Content -LiteralPath $social){if($line-match '^([A-Z_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}}
if($Docker) { docker compose --env-file $config up --build -d; if($LASTEXITCODE-ne 0){throw 'Docker startup failed'} }
else {
    $gradle=Get-Command gradle -ErrorAction SilentlyContinue
    if($gradle) { & $gradle.Source ':backend:runLocal' '--console=plain' } else { & '.\gradlew.bat' ':backend:runLocal' '--console=plain' }
    if($LASTEXITCODE-ne 0){throw 'Backend startup failed'}
}
