#Requires -Version 7.0
param(
    [Parameter(Mandatory)][uri]$Server,
    [Parameter(Mandatory)][string]$DeveloperName,
    [Parameter(Mandatory)][uri]$Website,
    [Parameter(Mandatory)][uri]$Support,
    [Parameter(Mandatory)][uri]$PrivacyPolicy,
    [Parameter(Mandatory)][uri]$Terms,
    [string]$IconPath = (Join-Path $PSScriptRoot '../docs/images/plugin-icon.png'),
    [string]$ClientId = 'habit-tracker-chatgpt',
    [string]$Version = '1.0.0',
    [string]$Output = (Join-Path $PSScriptRoot '../dist/habit-tracker-chatgpt.zip')
)
$ErrorActionPreference = 'Stop'
foreach ($url in @($Server, $Website, $Support, $PrivacyPolicy, $Terms)) {
    if (-not $url.IsAbsoluteUri -or $url.Scheme -ne 'https' -or $url.IsLoopback -or $url.UserInfo -or $url.Fragment) {
        throw 'Every listing and server address must be a public HTTPS URL.'
    }
}
if ($Server.AbsolutePath -ne '/' -or $Server.Query -or $Server.Host -match '(^|\.)(lhr\.life|trycloudflare\.com|localhost\.run)$') {
    throw 'Server must be a stable HTTPS origin. /mcp is added automatically.'
}
if ($IconPath -notmatch '\.png$') { throw 'Provide the PNG listing icon.' }
$logo = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $IconPath).Path)

function ReviewCase($prompt, $tools, $expected) {
    @{ prompt = $prompt; tools_triggered = $tools; expected_behavior = $expected }
}
$manifest = @{
    '$schema' = 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json'
    name = 'habit-tracker'; version = $Version
    description = 'Create, list and complete tasks, and save text notes in your connected Habit Tracker account.'
    author = @{ name = $DeveloperName }
    repository = 'https://github.com/narysh/habit-tracker'
    extensions = @{ 'com.openai' = @{
        interface = @{
            displayName = 'Habit Tracker'; shortDescription = 'Tasks and notes for your Android app'
            longDescription = 'Create, view and complete tasks and save text notes in your Habit Tracker account. The Android app syncs these records when it is online.'
            developerName = $DeveloperName; category = 'Productivity'; logo = './icon.png'; composerIcon = './icon.png'
            websiteURL = $Website.AbsoluteUri; supportURL = $Support.AbsoluteUri
            privacyPolicyURL = $PrivacyPolicy.AbsoluteUri; termsOfServiceURL = $Terms.AbsoluteUri
            defaultPrompt = @('Show my Habit Tracker tasks', 'Add a task to pay for internet', 'Save a note titled Trip with the text Bring the passport')
        }
        review = @{ test_cases = @{
            positive = @(
                (ReviewCase 'Add a Habit Tracker task called Buy milk, with no deadline.' 'create_task' 'One task named Buy milk appears in the connected account without a due date.')
                (ReviewCase 'Add a Habit Tracker task called Submit report for November 25, 2027.' 'create_task' 'One task appears with the requested date.')
                (ReviewCase 'List my Habit Tracker tasks.' 'get_tasks' 'Shows the tasks from the connected account.')
                (ReviewCase 'What unfinished tasks are in my Habit Tracker?' 'get_tasks' 'Reports unfinished tasks only.')
                (ReviewCase 'Mark my Habit Tracker task Buy milk complete.' 'get_tasks, complete_task' 'Only the selected existing task is marked complete.')
                (ReviewCase 'Save a Habit Tracker note titled Trip with the text Bring the passport.' 'create_note' 'One note with the requested title and text appears in the connected account.')
            )
            negative = @(
                (ReviewCase 'What is the capital of Spain?' '' 'Answers without calling Habit Tracker.')
                (ReviewCase 'Delete all my Habit Tracker tasks.' '' 'Explains deletion is unavailable and deletes nothing.')
                (ReviewCase 'Create a task in another Habit Tracker account using someone@example.test.' '' 'Explains it can act only in the connected account and creates nothing.')
                (ReviewCase 'Read my existing Habit Tracker notes.' '' 'Explains reading existing notes is unavailable and reveals no notes.')
            )
        } }
    } }
}
$mcp = @{
    '$schema' = 'https://agent-plugins.org/schemas/1.0.0/mcp.schema.json'
    mcpServers = @{ 'habit-tracker' = @{
        type = 'streamable-http'; url = $Server.AbsoluteUri.TrimEnd('/') + '/mcp'
        extensions = @{ 'com.openai' = @{ auth = @{
            type = 'oauth'
            client = @{ mode = 'provided'; clientId = $ClientId; tokenEndpointAuthMethod = 'client_secret_post' }
            baseScopes = @('tracker:read', 'tracker:write')
        } } }
    } }
}

$target = [IO.Path]::GetFullPath($Output)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
$zip = [IO.Compression.ZipFile]::Open($target, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in @{ 'plugin.json' = $manifest; 'mcp.json' = $mcp }.GetEnumerator()) {
        $writer = [IO.StreamWriter]::new($zip.CreateEntry($entry.Key).Open(), [Text.UTF8Encoding]::new($false))
        try { $writer.Write(($entry.Value | ConvertTo-Json -Depth 20)) } finally { $writer.Dispose() }
    }
    $stream = $zip.CreateEntry('icon.png').Open()
    try { $stream.Write($logo) } finally { $stream.Dispose() }
} finally { $zip.Dispose() }
Write-Output $target
