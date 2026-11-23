#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$OutputPath = ".local/hosted-connection-keys.env"
)

$ErrorActionPreference = "Stop"
$resolvedPath = [System.IO.Path]::GetFullPath($OutputPath)
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$localDirectory = [System.IO.Path]::GetFullPath((Join-Path $workspace ".local"))
if (-not $resolvedPath.StartsWith($localDirectory + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputPath must stay inside the ignored .local folder."
}

[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($resolvedPath)) | Out-Null
$key = [System.Security.Cryptography.ECDsa]::Create()
try {
    $curve = [System.Security.Cryptography.ECCurve]::CreateFromFriendlyName("nistP256")
    $key.GenerateKey($curve)
    $private = [Convert]::ToBase64String($key.ExportPkcs8PrivateKey())
    $public = [Convert]::ToBase64String($key.ExportSubjectPublicKeyInfo())
    $content = "CONNECTION_PRIVATE_KEY_BASE64=$private`nCONNECTION_PUBLIC_KEY_BASE64=$public`n"
    [System.IO.File]::WriteAllText($resolvedPath, $content, [System.Text.UTF8Encoding]::new($false))
} finally {
    $key.Dispose()
}

Write-Output "Stable backend identity keys saved locally to $resolvedPath. Do not upload this file or commit it."
