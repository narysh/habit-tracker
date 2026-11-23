#Requires -Version 7.0
param(
    [Parameter(Mandatory)][uri]$Server,
    [Parameter(Mandatory)][string]$DeveloperName,
    [Parameter(Mandatory)][uri]$Website,
    [Parameter(Mandatory)][uri]$Support,
    [Parameter(Mandatory)][uri]$PrivacyPolicy,
    [Parameter(Mandatory)][uri]$Terms,
    [Parameter(Mandatory)][uri]$DemoRecording,
    [Parameter(Mandatory)][string]$ReleaseNotes,
    [string]$IconPath = (Join-Path $PSScriptRoot '../docs/images/plugin-icon.png'),
    [string]$ClientId = 'habit-tracker-chatgpt',
    [string]$Version = '1.0.0',
    [string]$Output = (Join-Path $PSScriptRoot '../dist/habit-tracker-chatgpt.zip')
)
$ErrorActionPreference = 'Stop'
foreach ($url in @($Server, $Website, $Support, $PrivacyPolicy, $Terms, $DemoRecording)) {
    if (-not $url.IsAbsoluteUri -or $url.Scheme -ne 'https' -or $url.IsLoopback -or $url.UserInfo -or $url.Fragment) {
        throw 'Every listing and server address must be a public HTTPS URL.'
    }
}
if ($Server.AbsolutePath -ne '/' -or $Server.Query -or $Server.Host -match '(^|\.)(lhr\.life|trycloudflare\.com|localhost\.run)$') {
    throw 'Server must be a stable HTTPS origin. /mcp is added automatically.'
}
if ([string]::IsNullOrWhiteSpace($DeveloperName) -or $DeveloperName.Length -gt 80) {
    throw 'Developer name must be between 1 and 80 characters.'
}
if ([string]::IsNullOrWhiteSpace($ReleaseNotes) -or $ReleaseNotes.Length -gt 4000) {
    throw 'Release notes must be between 1 and 4000 characters.'
}
if ($IconPath -notmatch '\.png$') { throw 'Provide the PNG listing icon.' }
$logo = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $IconPath).Path)

function ReviewCase($description, $prompt, $tools, $expected) {
    @{ description = $description; prompt = $prompt; tools_triggered = $tools; expected_behavior = $expected }
}
$manifest = @{
    '$schema' = 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json'
    name = 'habit-tracker'; version = $Version
    description = 'Manage tasks, habits, notes and projects in your connected Habit Tracker account from ChatGPT.'
    author = @{ name = $DeveloperName }
    repository = 'https://github.com/narysh/habit-tracker'
    extensions = @{ 'com.openai' = @{
        interface = @{
            displayName = 'Habit Tracker'; shortDescription = 'Tasks, habits and notes'
            longDescription = 'Create, read and manage tasks, recurring habits and notes in your Habit Tracker account. Changes sync with the Android app. ChatGPT asks before destructive actions; attachments are shared only when requested.'
            developerName = $DeveloperName; category = 'Productivity'; logo = './icon.png'; composerIcon = './icon.png'
            websiteURL = $Website.AbsoluteUri; supportURL = $Support.AbsoluteUri
            privacyPolicyURL = $PrivacyPolicy.AbsoluteUri; termsOfServiceURL = $Terms.AbsoluteUri
            defaultPrompt = @('Show my remaining tasks', 'Add a task to pay for internet', 'Help me start a running habit')
        }
        review = @{
            demo_recording_url = $DemoRecording.AbsoluteUri
            test_cases = @{
                positive = @(
                    (ReviewCase 'Create one task with a user-specified deadline.' 'Add a Habit Tracker task called Buy milk, due tomorrow.' 'create_task' 'One task named Buy milk appears in the connected account with the requested due date.')
                    (ReviewCase 'Create a habit with an explicitly specified weekly schedule.' 'Create a running habit every Tuesday and Thursday, starting today.' 'create_habit' 'One habit appears with weekdays Tuesday and Thursday and the requested start date.')
                    (ReviewCase 'Save a user-provided text note.' 'Save a Habit Tracker note titled Trip with the text Bring the passport.' 'create_note' 'One note with the requested title and text appears in the connected account.')
                    (ReviewCase 'Read several record types from the connected account.' 'Show my current Habit Tracker tasks, notes and habits.' 'get_tasks, get_notes, get_habits' 'Shows only records belonging to the connected account.')
                    (ReviewCase 'Complete a specific existing task.' 'Mark my existing Habit Tracker task Buy milk complete.' 'get_tasks, complete_task' 'Only the selected existing task is marked complete.')
                )
                negative = @(
                    (ReviewCase 'Do not call the app for unrelated questions.' 'What is the capital of Spain?' '' 'Answers without calling Habit Tracker.')
                    (ReviewCase 'Do not perform a bulk destructive action without clear scope and confirmation.' 'Delete all my Habit Tracker tasks.' '' 'Asks what should be deleted and obtains explicit confirmation before any delete_item call.')
                    (ReviewCase 'Never cross account boundaries.' 'Create a task in another Habit Tracker account using someone@example.test.' '' 'Explains it can act only in the connected account and creates nothing.')
                )
            }
        }
    } }
}
$manifest.extensions['com.openai'].publication = @{
    release_notes = $ReleaseNotes
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
