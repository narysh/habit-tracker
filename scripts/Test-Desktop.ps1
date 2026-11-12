#Requires -Version 7.0
param([int]$ExpectedToolCount=3)
$ErrorActionPreference = 'Stop'
$start = [Diagnostics.ProcessStartInfo]::new((Get-Command codex -ErrorAction Stop).Source)
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
foreach ($argument in @('app-server','--stdio')) { $start.ArgumentList.Add($argument) }
$proxy = [Net.WebRequest]::DefaultWebProxy.GetProxy([uri]'https://example.com')
if ($proxy.Host -eq '127.0.0.1') { $start.Environment['HTTPS_PROXY'] = $proxy.AbsoluteUri; $start.Environment['HTTP_PROXY'] = $proxy.AbsoluteUri }
$start.Environment['NO_PROXY'] = '127.0.0.1,localhost'
$process = [Diagnostics.Process]::Start($start)
$errorTask = $process.StandardError.ReadToEndAsync()
function Send($message) {
    $process.StandardInput.WriteLine(($message | ConvertTo-Json -Depth 12 -Compress))
    $process.StandardInput.Flush()
}
function Receive($id) {
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
        $line = $process.StandardOutput.ReadLineAsync()
        if (-not $line.Wait(45000)) { throw 'Timed out waiting for the desktop client.' }
        if (-not $line.Result) { continue }
        $message = $line.Result | ConvertFrom-Json
        if ($message.id -eq $id) {
            if ($message.error) { throw "App-server rejected request: $($message.error.message)" }
            return $message.result
        }
    }
    throw 'Client exited before responding.'
}
try {
    Send @{id=1;method='initialize';params=@{clientInfo=@{name='habit-tracker-verification';version='0.1.0'}}}
    $null = Receive 1
    Send @{method='initialized';params=@{}}
    Send @{id=2;method='mcpServerStatus/list';params=@{serverName='habit-tracker';detail='toolsAndAuthOnly'}}
    $inventory = Receive 2
    $server = @($inventory.data) | Where-Object name -eq 'habit-tracker' | Select-Object -First 1
    if (-not $server) { throw 'Habit Tracker not found in client inventory.' }
    $names = @($server.tools.PSObject.Properties.Name | Sort-Object)
    [pscustomobject]@{Name=$server.name;AuthStatus=$server.authStatus;ToolNames=$names;ToolsError=$server.toolsError} | ConvertTo-Json -Compress
    if (@($names).Count -ne $ExpectedToolCount -or $server.toolsError) { throw 'MCP tool discovery did not succeed.' }
} finally {
    if (-not $process.HasExited) {
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(1500)) { $process.Kill() }
    }
    $process.Dispose()
}
