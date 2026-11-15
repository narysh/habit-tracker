#Requires -Version 7.0
param([string]$BaseUrl='http://127.0.0.1:8081')
$ErrorActionPreference='Stop'
$base=$BaseUrl.TrimEnd('/')
$password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$session=Invoke-RestMethod "$base/api/v1/auth/register" -Method Post -ContentType application/json -Body (@{email="features-$([guid]::NewGuid().ToString('N'))@test.invalid";password=$password}|ConvertTo-Json) -NoProxy
$headers=@{Authorization="Bearer $($session.token)";Accept='application/json, text/event-stream';'MCP-Protocol-Version'='2025-11-25'}
function Call-Mcp($name,$arguments){
    $body=@{jsonrpc='2.0';id=1;method='tools/call';params=@{name=$name;arguments=$arguments}}|ConvertTo-Json -Depth 15
    $reply=Invoke-RestMethod "$base/mcp" -Method Post -Headers $headers -ContentType application/json -Body ([Text.Encoding]::UTF8.GetBytes($body)) -NoProxy
    if($reply.result.isError){throw "MCP $name rejected the synthetic request"}
    $reply.result.content[0].text|ConvertFrom-Json
}
try {
    $monday=(Get-Date).Date.AddDays(-(((Get-Date).DayOfWeek.value__+6)%7)).ToString('yyyy-MM-dd')
    $next=([datetime]::ParseExact($monday,'yyyy-MM-dd',$null)).AddDays(2).ToString('yyyy-MM-dd')
    $project=Call-Mcp create_project @{operation_id=[guid]::NewGuid().ToString();title='MCP feature project';is_goal=$true}
    $task=Call-Mcp create_task @{operation_id=[guid]::NewGuid().ToString();title='MCP feature task';due_date=$monday;zone_id='Europe/Moscow'}
    $review=Call-Mcp get_week_review @{week_start=$monday;zone_id='Europe/Moscow'}
    if(-not ($review.remainingTasks|Where-Object id -eq $task.item.id)){throw 'Weekly review omitted the task'}
    $found=Call-Mcp search_records @{query='feature task'}
    if(-not ($found|Where-Object id -eq $task.item.id)){throw 'Search omitted the task'}
    $plan=Call-Mcp propose_week_plan @{operation_id=[guid]::NewGuid().ToString();title='MCP feature proposal';summary='Move a synthetic task';week_start=$monday;moves=@(@{itemId=$task.item.id;expectedVersion=$task.item.version;dueDate=$next;reason='Spread work'})}
    $before=Invoke-RestMethod "$base/api/v1/items/$($task.item.id)" -Headers $headers -NoProxy
    if($before.content.dueDate-ne $monday){throw 'Proposal changed a task without confirmation'}
    $applied=Invoke-RestMethod "$base/api/v1/week-plans/$($plan.item.id)/apply" -Method Post -Headers $headers -ContentType application/json -Body (@{expectedVersion=$plan.item.version}|ConvertTo-Json) -NoProxy
    $after=Invoke-RestMethod "$base/api/v1/items/$($task.item.id)" -Headers $headers -NoProxy
    if(-not $applied.content.applied -or $after.content.dueDate-ne $next){throw 'Confirmed plan did not apply'}
    Write-Output 'PASS: MCP projects, search, weekly review, proposal without changes and explicit atomic application (synthetic account).'
} finally {Invoke-RestMethod "$base/api/v1/logout" -Headers $headers -Method Post -NoProxy | Out-Null}
