#requires -Version 5.1
<#
.SYNOPSIS
  One-time local setup for a PRIVATE, persistent RoamGlyph development APK key.
.DESCRIPTION
  Creates a dedicated PKCS12 key in a user-chosen backup directory, and uploads
  its base64 representation and password to GitHub Actions Secrets via gh CLI.
  NEVER adds private key material to Git or echoes it in console output.
  Future development APKs from main use this identity for in-place updates.
.EXAMPLE
  .\scripts\setup-dev-signing.ps1 -BackupDirectory "D:\secure\roamglyph-signing"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory
)

$ErrorActionPreference = "Stop"
$repo = "efremov-aleksei-96/roamglyph"
$alias = "roamglyph-dev"

foreach ($command in @("keytool", "gh")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command '$command' was not found. Install JDK 17+ and GitHub CLI (gh), then sign in using 'gh auth login'."
    }
}
& gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated. Run 'gh auth login' and retry."
}

$folder = [System.IO.Path]::GetFullPath($BackupDirectory)
New-Item -Path $folder -ItemType Directory -Force | Out-Null
$keyPath = Join-Path $folder "roamglyph-dev.p12"
$passwordPath = Join-Path $folder "roamglyph-dev-password.txt"

$keyExists = Test-Path -LiteralPath $keyPath
$passwordExists = Test-Path -LiteralPath $passwordPath
if ($keyExists -ne $passwordExists) {
    throw "Only one signing credential file exists. Stop to avoid accidental key rotation. Restore the missing matching file from secure backup."
}
if (-not $keyExists) {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = [Convert]::ToBase64String($bytes).
        Replace('+', '-').Replace('/', '_').TrimEnd('=')
    $keyArgs = @(
        "-genkeypair", "-noprompt",
        "-keystore", $keyPath, "-storetype", "PKCS12",
        "-storepass", $password, "-keypass", $password,
        "-alias", $alias, "-keyalg", "RSA", "-keysize", "3072",
        "-validity", "36500",
        "-dname", "CN=RoamGlyph Development, OU=Test Builds, O=RoamGlyph, C=AM"
    )
    & keytool @keyArgs
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $keyPath)) {
        throw "Failed to generate development signing key."
    }
    [System.IO.File]::WriteAllText(
        $passwordPath, $password, [System.Text.Encoding]::ASCII)
    Write-Host "Created a new stable, private development signing identity."
}
else {
    $password = [System.IO.File]::ReadAllText($passwordPath).Trim()
    if (-not $password) { throw "Saved signing password is empty." }
    Write-Host "Reusing existing private development signing identity."
}

$listArgs = @("-list", "-storetype", "PKCS12", "-keystore", $keyPath,
    "-storepass", $password, "-alias", $alias)
& keytool @listArgs | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Private key, alias, or saved password mismatch. Secrets were not updated."
}

# gh secret set reads values from stdin. No private key or password is ever
# placed into command-line arguments, repository files or GitHub logs.
$encoded = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keyPath))
$encoded | & gh secret set ROAMGLYPH_DEV_KEYSTORE_BASE64 --repo $repo
if ($LASTEXITCODE -ne 0) { throw "Failed to set keystore secret." }
$password | & gh secret set ROAMGLYPH_DEV_KEYSTORE_PASSWORD --repo $repo
if ($LASTEXITCODE -ne 0) { throw "Failed to set password secret." }

Write-Host "GitHub development signing secrets configured successfully."
Write-Host "Private backup directory: $folder"
Write-Host "Keep BOTH files backed up in encrypted storage; losing either can prevent future seamless APK updates."
Write-Host "Existing dev.24 installs still require ONE final uninstall due to the previous incompatible signature."
