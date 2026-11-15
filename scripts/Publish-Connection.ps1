#Requires -Version 7.0
param([Parameter(Mandatory)][string]$Url)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$nonce=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')
$proof=Invoke-RestMethod "$Url/connection/proof?nonce=$nonce" -TimeoutSec 10
$key=[Security.Cryptography.ECDsa]::Create()
try {
    $read=0
    $key.ImportSubjectPublicKeyInfo([Convert]::FromBase64String((Get-Content (Join-Path $root 'config/server-public-key.txt') -Raw).Trim()),[ref]$read)
    $payload=[Text.Encoding]::UTF8.GetBytes("habit-tracker-connection-v1`n$Url`n$nonce")
    if($proof.url-ne $Url -or $proof.nonce-ne $nonce -or -not $key.VerifyData($payload,[Convert]::FromBase64String($proof.signature),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)){throw 'Server identity verification failed'}
} finally {$key.Dispose()}
$env:GIT_TERMINAL_PROMPT='0';$env:GCM_INTERACTIVE='Never'
$raw="protocol=https`nhost=github.com`n`n"|git credential fill
$credentials=@{};foreach($line in $raw){$p=$line-split '=',2;if($p.Count-eq 2){$credentials[$p[0]]=$p[1]}}
if(-not $credentials.password){throw 'GitHub credentials are required to publish the address'}
$headers=@{Authorization="Bearer $($credentials.password)";Accept='application/vnd.github+json'}
$api='https://api.github.com/repos/narysh/habit-tracker'
$ref=Invoke-WebRequest "$api/git/ref/heads/connection" -Headers $headers -SkipHttpErrorCheck
if([int]$ref.StatusCode-eq 404){
    $source=Invoke-RestMethod "$api/git/ref/heads/master" -Headers $headers
    Invoke-RestMethod "$api/git/refs" -Method Post -Headers $headers -ContentType application/json -Body (@{ref='refs/heads/connection';sha=$source.object.sha}|ConvertTo-Json)|Out-Null
} elseif([int]$ref.StatusCode-ne 200){throw 'Cannot inspect connection branch'}
$old=Invoke-WebRequest "$api/contents/connection.json?ref=connection" -Headers $headers -SkipHttpErrorCheck
$document=@{url=$Url}|ConvertTo-Json -Compress
$body=@{message='Update public Android connection address';branch='connection';content=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($document))}
if([int]$old.StatusCode-eq 200){
    $file=$old.Content|ConvertFrom-Json
    if([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($file.content)).Trim()-eq $document){Write-Output 'Automatic connection address is already up to date.';return}
    $body.sha=$file.sha
} elseif([int]$old.StatusCode-ne 404){throw 'Cannot read published address'}
Invoke-RestMethod "$api/contents/connection.json" -Method Put -Headers $headers -ContentType application/json -Body ($body|ConvertTo-Json) | Out-Null
Write-Output 'Published automatic Android connection address (public URL only, no credentials).'
