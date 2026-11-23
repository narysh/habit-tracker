#Requires -Version 7.0
$ErrorActionPreference = 'Stop'

$outputPath = Join-Path ([IO.Path]::GetTempPath()) ("habit-tracker-plugin-test-{0}.zip" -f [guid]::NewGuid())
try {
    & (Join-Path $PSScriptRoot 'Build-ChatGPT-Plugin.ps1') `
        -Server 'https://example.test' `
        -DeveloperName 'Habit Tracker test' `
        -Website 'https://example.test' `
        -Support 'https://example.test/support' `
        -PrivacyPolicy 'https://example.test/privacy' `
        -Terms 'https://example.test/terms' `
        -DemoRecording 'https://example.test/demo' `
        -ReleaseNotes 'Synthetic metadata validation only.' `
        -Output $outputPath | Out-Null

    $archive = [IO.Compression.ZipFile]::OpenRead($outputPath)
    try {
        $pluginEntry = $archive.GetEntry('plugin.json')
        $mcpEntry = $archive.GetEntry('mcp.json')
        if ($null -eq $pluginEntry -or $null -eq $mcpEntry -or $null -eq $archive.GetEntry('icon.png')) {
            throw 'The package must contain plugin.json, mcp.json and icon.png.'
        }

        $reader = [IO.StreamReader]::new($pluginEntry.Open())
        try { $plugin = $reader.ReadToEnd() | ConvertFrom-Json -AsHashtable } finally { $reader.Dispose() }
        $reader = [IO.StreamReader]::new($mcpEntry.Open())
        try { $mcp = $reader.ReadToEnd() | ConvertFrom-Json -AsHashtable } finally { $reader.Dispose() }

        $openai = $plugin.extensions['com.openai']
        $review = $openai.review
        if ($openai.interface.shortDescription.Length -gt 30) { throw 'The listing subtitle exceeds 30 characters.' }
        if ($review.test_cases.positive.Count -ne 5 -or $review.test_cases.negative.Count -ne 3) {
            throw 'The MCP review package must contain exactly five positive and three negative cases.'
        }
        if ([string]::IsNullOrWhiteSpace($review.demo_recording_url) -or
            [string]::IsNullOrWhiteSpace($plugin.extensions['com.openai'].publication.release_notes)) {
            throw 'The MCP review package must include a demo recording and release notes.'
        }
        foreach ($case in @($review.test_cases.positive) + @($review.test_cases.negative)) {
            if ([string]::IsNullOrWhiteSpace($case.description) -or [string]::IsNullOrWhiteSpace($case.prompt)) {
                throw 'Every review case must include a description and prompt.'
            }
        }
        foreach ($case in $review.test_cases.positive) {
            if ([string]::IsNullOrWhiteSpace($case.tools_triggered) -or [string]::IsNullOrWhiteSpace($case.expected_behavior)) {
                throw 'Every positive review case must include tools and expected behavior.'
            }
        }
        if ($mcp.mcpServers['habit-tracker'].url -ne 'https://example.test/mcp') {
            throw 'The package MCP URL does not point to the expected /mcp endpoint.'
        }
        if ($mcp.mcpServers['habit-tracker'].extensions['com.openai'].auth.baseScopes -notcontains 'tracker:write') {
            throw 'The package OAuth configuration must request the write scope.'
        }
    } finally {
        $archive.Dispose()
    }
    Write-Output 'PASS: generated ChatGPT package contains the required listing and MCP review metadata.'
} finally {
    Remove-Item -LiteralPath $outputPath -Force -ErrorAction SilentlyContinue
}
