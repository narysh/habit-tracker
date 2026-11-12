#Requires -Version 7.0
param([switch]$UpdateExisting,[switch]$Backend,[switch]$DemoAccount)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot $(if($Backend){'.local/backend.env'}else{'.local/probe.env'}))) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
if($Backend) {
    $serverUrl=$values.PUBLIC_URL.TrimEnd('/')+'/mcp'
    $values.OAUTH_CLIENT_ID=$values.MCP_CLIENT_ID; $values.OAUTH_CLIENT_SECRET=$values.MCP_CLIENT_SECRET
    $callbackPort=45838
    if($DemoAccount) {
        $accountFile=Join-Path $projectRoot '.local/demo-account.env'
        if(-not (Test-Path -LiteralPath $accountFile)) {
            $random=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')
            @("EMAIL=demo-$([guid]::NewGuid().ToString('N').Substring(0,8))@habit.local","PASSWORD=$random")|Set-Content -LiteralPath $accountFile -Encoding utf8
        }
        $account=@{};foreach($line in Get-Content -LiteralPath $accountFile){$parts=$line-split '=',2;if($parts.Count-eq 2){$account[$parts[0]]=$parts[1]}}
        $loginEmail=$account.EMAIL; $loginPassword=$account.PASSWORD
        $body=@{email=$loginEmail;password=$loginPassword}|ConvertTo-Json -Compress
        $apiBase=$values.PUBLIC_URL.TrimEnd('/')
        $login=Invoke-WebRequest "$apiBase/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body -SkipHttpErrorCheck
        if([int]$login.StatusCode-eq 401){$login=Invoke-WebRequest "$apiBase/api/v1/auth/register" -Method Post -ContentType 'application/json' -Body $body -SkipHttpErrorCheck}
        if([int]$login.StatusCode-notin @(200,201)){throw 'Cannot authenticate demo account. No credentials were printed.'}
        Write-Output 'Using a separate demo account; its credentials are in .local/demo-account.env.'
    } else {
        $loginEmail=Read-Host 'Email аккаунта Habit Tracker'
        $secure=Read-Host 'Пароль аккаунта Habit Tracker' -AsSecureString
        $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try{$loginPassword=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
    }
} else {
    $instructions = Get-Content -LiteralPath (Join-Path $projectRoot '.local/connection.md') -Raw
    if ($instructions -notmatch 'Server URL: (https?://[^\s]+/mcp)') { throw 'Missing current server URL.' }
    $serverUrl = $Matches[1]; $callbackPort=45837
}
$configPath = Join-Path $env:USERPROFILE '.codex/config.toml'
$configText = [IO.File]::ReadAllText($configPath)
if ($configText -match '(?m)^\[mcp_servers\.habit-tracker(?:\.|\])' -and -not $UpdateExisting) {
    throw 'habit-tracker is already configured; do not overwrite an existing connection automatically.'
}
$backupDir = Join-Path $projectRoot '.local/backups'
New-Item -ItemType Directory -Force $backupDir | Out-Null
Copy-Item -LiteralPath $configPath -Destination (Join-Path $backupDir ('config-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.toml'))
$addition = @"

[mcp_servers.habit-tracker]
url = "$serverUrl"
enabled = true
default_tools_approval_mode = "writes"
scopes = ["tracker:read", "tracker:write"]
startup_timeout_sec = 30

[mcp_servers.habit-tracker.oauth]
client_id = "$($values.OAUTH_CLIENT_ID)"
client_secret = "$($values.OAUTH_CLIENT_SECRET)"
callback_url = "http://127.0.0.1:$callbackPort/callback"
callback_port = $callbackPort
"@
if ($UpdateExisting) {
    $sectionPattern = '(?ms)^\[mcp_servers\.habit-tracker\]\r?\n.*?(?=^\[(?!mcp_servers\.habit-tracker(?:\.|\]))|\z)'
    $section = [regex]::Match($configText, $sectionPattern)
    if (-not $section.Success -or -not ($section.Value.Contains('client_id = "' + $values.OAUTH_CLIENT_ID + '"') -or ($Backend -and $section.Value.Contains('client_id = "habit-tracker-probe"')))) {
        throw 'Existing server does not match this probe; refusing to change it.'
    }
    $next = $configText.Substring(0, $section.Index) + $addition.TrimStart() + "`n" + $configText.Substring($section.Index + $section.Length)
} else { $next = $configText.TrimEnd() + "`n" + $addition + "`n" }
[IO.File]::WriteAllText($configPath, $next, [Text.UTF8Encoding]::new($false))
Write-Output 'Registered habit-tracker in the shared desktop/CLI MCP configuration. Existing settings preserved.'

# This logs into our disposable probe, not into the user's ChatGPT account.
# Keep credentials, authorization URLs and callback codes out of process arguments and logs.
$start = [Diagnostics.ProcessStartInfo]::new((Get-Command codex -ErrorAction Stop).Source)
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
foreach ($argument in @('mcp','login','habit-tracker','--no-browser')) { $start.ArgumentList.Add($argument) }
$proxy = [Net.WebRequest]::DefaultWebProxy.GetProxy([uri]$serverUrl)
if ($proxy.Host -eq '127.0.0.1') { $start.Environment['HTTPS_PROXY'] = $proxy.AbsoluteUri; $start.Environment['HTTP_PROXY'] = $proxy.AbsoluteUri }
$start.Environment['NO_PROXY'] = '127.0.0.1,localhost'
$process = [Diagnostics.Process]::Start($start)
$errorTask = $process.StandardError.ReadToEndAsync()
try {
    $authorizationUrl = $null
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
        $lineTask = $process.StandardOutput.ReadLineAsync()
        if (-not $lineTask.Wait(30000)) { throw 'CLI did not return an authorization URL in time.' }
        $line = $lineTask.Result
        if ($line -match '(https?://[^\s]+/oauth/authorize\?[^\s]+)') { $authorizationUrl = $Matches[1]; break }
    }
    if (-not $authorizationUrl) { throw 'CLI did not produce an OAuth URL. See the sanitized result below.' }
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    if (([uri]$authorizationUrl).IsLoopback) { $handler.UseProxy = $false }
    $http = [Net.Http.HttpClient]::new($handler)
    $http.Timeout = [TimeSpan]::FromSeconds(15)
    try {
        $page = $http.GetAsync($authorizationUrl).GetAwaiter().GetResult()
        if (-not $page.IsSuccessStatusCode) { throw "Probe rejected OAuth authorization request (HTTP $([int]$page.StatusCode))." }
        $pageText = $page.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($pageText -notmatch 'name="nonce" value="([^"]+)"') { throw 'Consent nonce missing.' }
        $form = [Collections.Generic.Dictionary[string,string]]::new()
        $form.Add('nonce', $Matches[1]); $form.Add('decision','allow')
        if($Backend){$form.Add('email',$loginEmail);$form.Add('password',$loginPassword)}else{$form.Add('secret',$values.PAIRING_SECRET)}
        $body = [Net.Http.FormUrlEncodedContent]::new($form)
        $consentUrl = $serverUrl.Substring(0, $serverUrl.Length - '/mcp'.Length) + '/oauth/consent'
        $response = $http.PostAsync($consentUrl, $body).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 302) { throw "Probe consent failed (HTTP $([int]$response.StatusCode))." }
        $process.StandardInput.WriteLine($response.Headers.Location.AbsoluteUri)
        $process.StandardInput.Flush()
        $process.StandardInput.Close()
    } finally { $http.Dispose(); $handler.Dispose() }
    if (-not $process.WaitForExit(30000)) { throw 'CLI did not finish token exchange.' }
    if ($process.ExitCode -ne 0) { throw 'OAuth token exchange failed.' }
    Write-Output 'OAuth login succeeded. No secrets or authorization codes were printed.'
} catch {
    if (-not $process.HasExited) { $process.Kill() }
    # Avoid dumping CLI diagnostics containing OAuth URLs, state or codes.
    throw
} finally { $process.Dispose() }
