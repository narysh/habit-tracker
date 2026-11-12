#Requires -Version 7.0
param(
    [switch]$LocalOnly,
    [string]$PublicUrl,
    [ValidateSet('localhost.run','cloudflare')][string]$TunnelProvider = 'localhost.run',
    [ValidateSet('auto','http2','quic')][string]$TunnelProtocol = 'auto'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$localDir = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Force $localDir | Out-Null
$stateFile = Join-Path $localDir 'processes.json'
if (Test-Path -LiteralPath $stateFile) { throw 'Run scripts/Stop-Probe.ps1 before starting another probe.' }
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { throw 'Port 8080 is already in use.' }

$gradleCommand = Get-Command gradle -ErrorAction SilentlyContinue
if ($gradleCommand) { $gradleExecutable = $gradleCommand.Source } else { $gradleExecutable = Join-Path $projectRoot 'gradlew.bat' }
& $gradleExecutable ':mcp-probe:installDist' '--console=plain'
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
$distributionLine = & $gradleExecutable ':mcp-probe:printDistributionPath' '-q' | Where-Object { $_ -like 'PROBE_DIST=*' } | Select-Object -Last 1
if (-not $distributionLine) { throw 'Cannot locate server distribution.' }
$distribution = $distributionLine.Substring('PROBE_DIST='.Length)

function New-Secret {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
$envFile = Join-Path $localDir 'probe.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    @('OAUTH_CLIENT_ID=habit-tracker-probe', "OAUTH_CLIENT_SECRET=$(New-Secret)", "PAIRING_SECRET=$(New-Secret)",
      'OAUTH_REDIRECT_URIS=https://chatgpt.com/connector_platform_oauth_redirect') | Set-Content -LiteralPath $envFile -Encoding utf8
}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}

$processRecords = @()
function Record-Process($process, $role) {
    @{ id = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o'); role = $role }
}
try {
    if (-not $PublicUrl -and -not $LocalOnly) {
        if ($TunnelProvider -eq 'cloudflare') {
            $tunnelExecutable = Join-Path $projectRoot '.tools\cloudflared.exe'
            if (-not (Test-Path -LiteralPath $tunnelExecutable)) { throw 'Run scripts/Install-Tunnel.ps1 first.' }
            $tunnelArguments = @('tunnel','--no-autoupdate','--protocol',$TunnelProtocol,'--url','http://127.0.0.1:8080')
        } else {
            $tunnelExecutable = (Get-Command ssh -ErrorAction Stop).Source
            $knownHosts = Join-Path $localDir 'ssh_known_hosts'
            $tunnelArguments = @('-T','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','IdentityAgent=none','-o','IdentityFile=none',
                '-o','StrictHostKeyChecking=accept-new','-o',"`"UserKnownHostsFile=$knownHosts`"",'-o','ConnectTimeout=10',
                '-o','ServerAliveInterval=30','-o','ExitOnForwardFailure=yes','-R','80:127.0.0.1:8080','nokey@localhost.run')
        }
        $tunnel = Start-Process -FilePath $tunnelExecutable -ArgumentList $tunnelArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $localDir 'tunnel.out.log') -RedirectStandardError (Join-Path $localDir 'tunnel.err.log')
        $processRecords += Record-Process $tunnel 'tunnel'
        $deadline = (Get-Date).AddSeconds(40)
        do {
            Start-Sleep -Milliseconds 300
            $log = (Get-Content -LiteralPath (Join-Path $localDir 'tunnel.err.log') -Raw -ErrorAction SilentlyContinue) + (Get-Content -LiteralPath (Join-Path $localDir 'tunnel.out.log') -Raw -ErrorAction SilentlyContinue)
            if ($TunnelProvider -eq 'cloudflare' -and $log -match 'https://[a-z0-9-]+\.trycloudflare\.com') { $PublicUrl = $Matches[0] }
            if ($TunnelProvider -eq 'localhost.run' -and $log -match 'tunneled with tls termination,\s+(https://[a-z0-9.-]+)') { $PublicUrl = $Matches[1] }
            $tunnel.Refresh()
        } while (-not $PublicUrl -and -not $tunnel.HasExited -and (Get-Date) -lt $deadline)
        if (-not $PublicUrl) { throw 'Tunnel did not return a URL. See .local/tunnel.err.log.' }
    }
    if ($LocalOnly) { $PublicUrl = 'http://127.0.0.1:8080' }
    $env:PUBLIC_URL = $PublicUrl.TrimEnd('/')
    $env:PROBE_DATA_FILE = Join-Path $localDir 'probe-tasks.json'
    $javaExecutable = (Get-Command java -ErrorAction Stop).Source
    $classpath = Join-Path $distribution 'lib\*'
    $server = Start-Process -FilePath $javaExecutable -ArgumentList @('-cp', "`"$classpath`"", 'dev.habittracker.probe.MainKt') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $localDir 'server.out.log') -RedirectStandardError (Join-Path $localDir 'server.err.log')
    $processRecords += Record-Process $server 'server'
    $processRecords | ConvertTo-Json -AsArray | Set-Content -LiteralPath $stateFile -Encoding utf8
    $deadline = (Get-Date).AddSeconds(15)
    $healthy = $false
    do {
        Start-Sleep -Milliseconds 300
        try { $healthy = (Invoke-RestMethod 'http://127.0.0.1:8080/health' -NoProxy).status -eq 'ok' } catch {}
        $server.Refresh()
    } while (-not $healthy -and -not $server.HasExited -and (Get-Date) -lt $deadline)
    if (-not $healthy) { throw 'Server did not become healthy. See .local/server.err.log.' }
    @"
# Подключение к ChatGPT

Server URL: $env:PUBLIC_URL/mcp
Authentication: OAuth, predefined client credentials (client_secret_post)
Client ID: $env:OAUTH_CLIENT_ID
Client secret: возьми OAUTH_CLIENT_SECRET из .local/probe.env
Scopes: tracker:read tracker:write

На странице подключения введи PAIRING_SECRET из .local/probe.env, затем нажми «Разрешить».
Не отправляй секреты в чат. Если ChatGPT показывает другую callback URI, добавь её точно в
OAUTH_REDIRECT_URIS в .local/probe.env (через запятую) и перезапусти сервер.

Проверка: создай задачу «Проверить MCP», получи список, затем попроси удалить её и ОТМЕНИ
подтверждение. Повторно получи список: задача должна остаться. Затем подтверди удаление.

Доступ действует один час; затем подключись заново. После перезапуска сервера подключись заново.
Остановка: ./scripts/Stop-Probe.ps1
"@ | Set-Content -LiteralPath (Join-Path $localDir 'connection.md') -Encoding utf8
    Write-Output "Local server ready. MCP URL: $env:PUBLIC_URL/mcp"
    if (-not $LocalOnly) {
        try {
            $remote = Invoke-RestMethod "$env:PUBLIC_URL/health" -TimeoutSec 10
            if ($remote.status -ne 'ok') { throw 'Unexpected public health response.' }
            Write-Output 'Public HTTPS health check passed.'
        } catch {
            Write-Warning 'Public HTTPS check failed. The server is local-only until the tunnel connects. See .local/tunnel.err.log.'
        }
    }
    Write-Output 'Connection instructions: .local/connection.md (secrets stay in .local/probe.env)'
} catch {
    foreach ($record in $processRecords) {
        $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$record.started).ToUniversalTime().Ticks) { Stop-Process -Id $record.id }
    }
    if (Test-Path -LiteralPath $stateFile) { Remove-Item -LiteralPath $stateFile }
    throw
}
