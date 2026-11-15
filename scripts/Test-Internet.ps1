#Requires -Version 7.0
param([string]$Url)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(-not $Url){$Url=(Get-Content (Join-Path $root '.local/backend-public-url.txt') -Raw).Trim()}
if(([uri]$Url).Scheme-ne 'https'){throw 'Public test requires HTTPS'}
$password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$credentials=@{email="internet-$([guid]::NewGuid().ToString('N'))@test.invalid";password=$password}|ConvertTo-Json
$session=Invoke-RestMethod "$Url/api/v1/auth/register" -Method Post -ContentType application/json -Body $credentials
$headers=@{Authorization="Bearer $($session.token)"}
$id=[guid]::NewGuid().ToString()
$content=@{kind='task';title='HTTPS smoke test'}
function Send-Mutation($version,$deleted) {
    $body=@{operationId=[guid]::NewGuid().ToString();itemId=$id;baseVersion=$version;content=$content;deleted=$deleted}|ConvertTo-Json -Depth 5
    Invoke-RestMethod "$Url/api/v1/mutations" -Headers $headers -Method Post -ContentType application/json -Body $body
}
try {
    $created=Send-Mutation 0 $false
    if($created.status-ne 'APPLIED'){throw 'Create failed'}
    $sync=Invoke-RestMethod "$Url/api/v1/sync" -Headers $headers -Method Post -ContentType application/json -Body '{"operations":[],"cursor":0}'
    if(-not ($sync.changes | Where-Object id -eq $id)){throw 'Task missing from sync feed'}
    $deleted=Send-Mutation $created.item.version $true
    if($deleted.status-ne 'APPLIED'){throw 'Cleanup failed'}
    Write-Output 'PASS: HTTPS registration, create, sync and deletion (synthetic test account).'
} finally {
    Invoke-RestMethod "$Url/api/v1/logout" -Headers $headers -Method Post | Out-Null
}
