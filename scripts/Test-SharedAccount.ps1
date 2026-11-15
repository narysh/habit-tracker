#Requires -Version 7.0
param([string]$BaseUrl="http://127.0.0.1:8081",[string]$AndroidBaseUrl="http://10.0.2.2:8081",[switch]$Synthetic)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$values=@{}
if($Synthetic){$values.EMAIL="fixture-$([guid]::NewGuid().ToString('N'))@test.invalid";$values.PASSWORD=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))}else{
foreach($line in Get-Content -LiteralPath .local/demo-account.env){$pair=$line-split '=',2;if($pair.Count-eq 2){$values[$pair[0]]=$pair[1]}}
}
$credentials=@{email=$values.EMAIL;password=$values.PASSWORD}|ConvertTo-Json -Compress
$session=Invoke-RestMethod "$BaseUrl/api/v1/auth/$(if($Synthetic){'register'}else{'login'})" -Method Post -ContentType 'application/json' -Body $credentials -NoProxy
$headers=@{Authorization="Bearer $($session.token)";Accept='application/json, text/event-stream';'MCP-Protocol-Version'='2025-11-25'}
function Invoke-Mcp($name,$arguments) {
    $body=@{jsonrpc='2.0';id=1;method='tools/call';params=@{name=$name;arguments=$arguments}}|ConvertTo-Json -Depth 10 -Compress
    $response=Invoke-RestMethod "$BaseUrl/mcp" -Method Post -Headers $headers -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -NoProxy
    if($response.result.isError){throw 'MCP rejected fixture operation'}
}
$fixtureOperations=Join-Path $projectRoot '.local/fixture-operations.json'
if(Test-Path -LiteralPath $fixtureOperations){$ids=Get-Content -LiteralPath $fixtureOperations -Raw|ConvertFrom-Json}else{
    $ids=@{task=[guid]::NewGuid().ToString();habit=[guid]::NewGuid().ToString();note=[guid]::NewGuid().ToString()}
    $ids|ConvertTo-Json|Set-Content -LiteralPath $fixtureOperations -Encoding utf8
}
Invoke-Mcp 'create_task' @{operation_id=$ids.task;title='Задача из MCP';description='Создана через MCP и синхронизирована с Android'}
Invoke-Mcp 'create_habit' @{operation_id=$ids.habit;title='Чтение из MCP';mode='WEEKLY_TARGET';target_per_week=3;starts_on='2026-11-12';zone_id='Europe/Moscow'}
Invoke-Mcp 'create_note' @{operation_id=$ids.note;title='Заметка из MCP';text='Этот текст пришёл через тот же backend, что использует Android-приложение.'}
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
& $adb -s emulator-5554 shell run-as dev.habittracker.android mkdir -p files
# Only the dedicated fixture's credentials travel over stdin into app-private debug storage.
$credentials | & $adb -s emulator-5554 shell run-as dev.habittracker.android sh -c '"cat > files/mcp-fixture.json"'
if($LASTEXITCODE-ne 0){throw 'Could not pass the fixture into the test app'}
$result=& $adb -s emulator-5554 shell am instrument -w -e backend_url $AndroidBaseUrl -e class dev.habittracker.android.SharedAccountTest dev.habittracker.android.test/androidx.test.runner.AndroidJUnitRunner
$result|Write-Output
if(($result-join "`n")-notmatch 'OK \(1 test\)'){throw 'Shared-account test failed'}
