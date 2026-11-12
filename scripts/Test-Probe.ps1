#Requires -Version 7.0
param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot '.local/probe.env')) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
if (([uri]$BaseUrl).IsLoopback) { $handler.UseProxy = $false }
$http = [System.Net.Http.HttpClient]::new($handler)
$http.Timeout = [TimeSpan]::FromSeconds(15)

function Form($values) {
    $dictionary = [Collections.Generic.Dictionary[string,string]]::new()
    foreach ($key in $values.Keys) { $dictionary.Add($key, [string]$values[$key]) }
    [System.Net.Http.FormUrlEncodedContent]::new($dictionary)
}
function Post-Form($path, $values) {
    $content = Form $values
    try { $http.PostAsync("$BaseUrl$path", $content).GetAwaiter().GetResult() } finally { $content.Dispose() }
}
function Read-Json($response) {
    if (-not $response.IsSuccessStatusCode) { throw "Unexpected HTTP status $([int]$response.StatusCode)" }
    $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
}
function Rpc($method, $parameters) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BaseUrl/mcp")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $script:accessToken)
    $request.Headers.Add('Accept', 'application/json, text/event-stream')
    $request.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    $payload = @{jsonrpc='2.0';id=1;method=$method;params=$parameters} | ConvertTo-Json -Depth 12 -Compress
    $request.Content = [System.Net.Http.StringContent]::new($payload, [Text.Encoding]::UTF8, 'application/json')
    try { Read-Json ($http.SendAsync($request).GetAwaiter().GetResult()) } finally { $request.Dispose() }
}

try {
    $metadata = Read-Json ($http.GetAsync("$BaseUrl/.well-known/oauth-protected-resource").GetAwaiter().GetResult())
    $resource = $metadata.resource
    $verifier = 'v' * 43
    $challenge = [Convert]::ToBase64String([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($verifier))).TrimEnd('=').Replace('+','-').Replace('/','_')
    $redirectUri = ($values.OAUTH_REDIRECT_URIS -split ',')[0]
    $authorize = @{client_id=$values.OAUTH_CLIENT_ID;redirect_uri=$redirectUri;response_type='code';state='smoke-test';code_challenge=$challenge;code_challenge_method='S256';scope='tracker:read tracker:write';resource=$resource}
    $query = ($authorize.GetEnumerator() | ForEach-Object { "{0}={1}" -f [uri]::EscapeDataString($_.Key),[uri]::EscapeDataString($_.Value) }) -join '&'
    $page = $http.GetAsync("$BaseUrl/oauth/authorize?$query").GetAwaiter().GetResult()
    if (-not $page.IsSuccessStatusCode) { throw 'Authorization page unavailable.' }
    $html = $page.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if ($html -notmatch 'name="nonce" value="([^"]+)"') { throw 'Consent nonce missing.' }
    $consent = Post-Form '/oauth/consent' @{nonce=$Matches[1];secret=$values.PAIRING_SECRET;decision='allow'}
    if ([int]$consent.StatusCode -ne 302) { throw 'Consent failed.' }
    $location = $consent.Headers.Location
    $code = (($location.Query.TrimStart('?') -split '&') | Where-Object { $_ -like 'code=*' }).Substring(5)
    $tokenResponse = Read-Json (Post-Form '/oauth/token' @{client_id=$values.OAUTH_CLIENT_ID;client_secret=$values.OAUTH_CLIENT_SECRET;grant_type='authorization_code';code=[uri]::UnescapeDataString($code);redirect_uri=$redirectUri;resource=$resource;code_verifier=$verifier})
    $script:accessToken = $tokenResponse.access_token
    $tools = (Rpc 'tools/list' @{}).result.tools
    if ($tools.Count -ne 3) { throw 'Unexpected tool count.' }
    $arguments = @{title='HTTP smoke test';operation_id=[guid]::NewGuid().ToString()}
    $creation = (Rpc 'tools/call' @{name='create_task';arguments=$arguments}).result
    if ($creation.isError) { throw 'Task creation failed.' }
    $task = $creation.content[0].text | ConvertFrom-Json
    $retry = (Rpc 'tools/call' @{name='create_task';arguments=$arguments}).result
    if ($retry.isError -or ($retry.content[0].text | ConvertFrom-Json).id -ne $task.id) { throw 'Idempotency failed.' }
    $list = (Rpc 'tools/call' @{name='get_tasks';arguments=@{}}).result.content[0].text | ConvertFrom-Json
    if ($task.id -notin @($list.id)) { throw 'Created task missing.' }
    $deleted = (Rpc 'tools/call' @{name='delete_task';arguments=@{id=$task.id;expected_version=$task.version}}).result
    if ($deleted.isError) { throw 'Deletion failed.' }
    $revoked = Post-Form '/oauth/revoke' @{client_id=$values.OAUTH_CLIENT_ID;client_secret=$values.OAUTH_CLIENT_SECRET;token=$script:accessToken}
    if (-not $revoked.IsSuccessStatusCode) { throw 'Revocation failed.' }
    Write-Output 'PASS: HTTP OAuth + PKCE, MCP discovery, create, retry, read, delete, revoke. Secrets were not printed.'
} finally { $http.Dispose(); $handler.Dispose(); $script:accessToken = $null }
