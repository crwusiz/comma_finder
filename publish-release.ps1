[CmdletBinding()]
param(
    [string]$NotesFile,
    [switch]$Draft
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoDir = $PSScriptRoot
$gradleRoot = (Resolve-Path (Join-Path $repoDir "..")).Path
$gradlew = Join-Path $gradleRoot "gradlew.bat"
$defaultKeystore = "C:\app\build_apk\keystore"
$savedEnvironment = @{}
$localConfig = Join-Path $repoDir "publish-release.local.ps1"

if (Test-Path -LiteralPath $localConfig -PathType Leaf) {
    . $localConfig
}

function Assert-LastExitCode([string]$message) {
    if ($LASTEXITCODE -ne 0) {
        throw $message
    }
}

function Test-NativeSuccess([scriptblock]$command) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $command *> $null
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Set-TemporaryEnvironment([string]$name, [string]$value) {
    if (-not $savedEnvironment.ContainsKey($name)) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
    }
    [Environment]::SetEnvironmentVariable($name, $value, "Process")
}

function Read-Secret([string]$prompt) {
    $secureValue = Read-Host $prompt -AsSecureString
    return [System.Net.NetworkCredential]::new("", $secureValue).Password
}

Push-Location $repoDir
try {
    if (-not (Test-Path -LiteralPath $gradlew -PathType Leaf)) {
        throw "Gradle wrapper not found: $gradlew"
    }

    $ghCommand = Get-Command gh -ErrorAction SilentlyContinue
    $gh = if ($ghCommand) { $ghCommand.Source } else { "C:\Program Files\GitHub CLI\gh.exe" }
    if (-not (Test-Path -LiteralPath $gh -PathType Leaf)) {
        throw "GitHub CLI is not installed. Run: winget install --id GitHub.cli -e"
    }

    if (-not (Test-NativeSuccess { & $gh auth status })) {
        throw "GitHub login is required. Run 'gh auth login' and retry."
    }

    $dirty = @(git status --porcelain --untracked-files=normal)
    Assert-LastExitCode "Unable to read Git status."
    if ($dirty.Count -gt 0) {
        throw "Commit all app repository changes before publishing a release."
    }

    $versionOutput = @(& $gradlew -q :app:printAppVersionName)
    Assert-LastExitCode "Unable to read appVersionName from Gradle."
    $version = ($versionOutput | Select-Object -Last 1).Trim()
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw "appVersionName is empty."
    }
    $tag = $version

    $headCommit = (git rev-parse HEAD).Trim()
    Assert-LastExitCode "Unable to read the current Git commit."
    $matchingLocalTags = @(git tag --list $tag)
    Assert-LastExitCode "Unable to inspect local Git tags."
    if ($matchingLocalTags.Count -gt 0) {
        $localTagCommit = (git rev-list -n 1 "refs/tags/$tag").Trim()
        Assert-LastExitCode "Unable to inspect local Git tag '$tag'."
        if ($localTagCommit.Trim() -ne $headCommit) {
            throw "Tag '$tag' already points to another commit. Increase appVersionName first."
        }
    } else {
        $localTagCommit = $null
    }

    $keystorePath = if ($env:CF_KEYSTORE_PATH) { $env:CF_KEYSTORE_PATH } else { $defaultKeystore }
    if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
        throw "Keystore not found: $keystorePath"
    }
    Set-TemporaryEnvironment "CF_KEYSTORE_PATH" $keystorePath

    if (-not $env:CF_KEY_ALIAS) {
        $keyAlias = Read-Host "Keystore key alias"
        if ([string]::IsNullOrWhiteSpace($keyAlias)) {
            throw "Key alias is required."
        }
        Set-TemporaryEnvironment "CF_KEY_ALIAS" $keyAlias
    }

    if (-not $env:CF_STORE_PASSWORD) {
        $storePassword = Read-Secret "Keystore password"
        if ([string]::IsNullOrEmpty($storePassword)) {
            throw "Keystore password is required."
        }
        Set-TemporaryEnvironment "CF_STORE_PASSWORD" $storePassword
    }

    if (-not $env:CF_KEY_PASSWORD) {
        $keyPassword = Read-Secret "Key password (press Enter if same as keystore password)"
        if ([string]::IsNullOrEmpty($keyPassword)) {
            $keyPassword = $env:CF_STORE_PASSWORD
        }
        Set-TemporaryEnvironment "CF_KEY_PASSWORD" $keyPassword
    }

    Write-Host "Building signed APK for version $version..." -ForegroundColor Cyan
    & $gradlew :app:clean :app:assembleRelease
    Assert-LastExitCode "Signed release build failed."

    $apkPath = Join-Path $repoDir "build\outputs\apk\release\comma_finder_v$version.apk"
    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        throw "Release APK was not created at the expected path: $apkPath"
    }

    $sdkRoot = if ($env:ANDROID_SDK_ROOT) {
        $env:ANDROID_SDK_ROOT
    } elseif ($env:ANDROID_HOME) {
        $env:ANDROID_HOME
    } else {
        Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot "build-tools") -Directory |
        Sort-Object { [version]$_.Name } -Descending
    $apkSigner = $buildTools | ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (-not $apkSigner) {
        throw "apksigner.bat was not found under: $sdkRoot"
    }

    & $apkSigner verify --verbose --print-certs $apkPath
    Assert-LastExitCode "APK signature verification failed."

    if (-not $localTagCommit) {
        git tag -a $tag -m "Release $tag"
        Assert-LastExitCode "Unable to create Git tag '$tag'."
    }

    $remoteTag = @(git ls-remote --tags origin "refs/tags/$tag")
    if ($remoteTag.Count -eq 0) {
        git push origin "refs/tags/$tag"
        Assert-LastExitCode "Unable to push Git tag '$tag'."
    }

    if (Test-NativeSuccess { & $gh release view $tag }) {
        & $gh release upload $tag $apkPath --clobber
        Assert-LastExitCode "Unable to upload APK to existing GitHub Release '$tag'."
    } else {
        $releaseArguments = @(
            "release", "create", $tag, $apkPath,
            "--verify-tag",
            "--title", $tag
        )
        if ($NotesFile) {
            $resolvedNotes = (Resolve-Path -LiteralPath $NotesFile).Path
            $releaseArguments += @("--notes-file", $resolvedNotes)
        } else {
            $releaseArguments += "--generate-notes"
        }
        if ($Draft) {
            $releaseArguments += "--draft"
        }
        & $gh @releaseArguments
        Assert-LastExitCode "Unable to create GitHub Release '$tag'."
    }

    Write-Host "Published GitHub Release $tag with $([IO.Path]::GetFileName($apkPath))." -ForegroundColor Green
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
    Pop-Location
}
