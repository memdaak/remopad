[CmdletBinding()]
param(
    [switch]$Release,
    [switch]$Install
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$java = Get-Command java.exe -ErrorAction SilentlyContinue
if ($null -eq $java -and [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'Java was not found. Install JDK 17 or set JAVA_HOME.'
}

$sdkRoot = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $sdkRoot = $env:ANDROID_HOME
}
if ([string]::IsNullOrWhiteSpace($sdkRoot) -or -not (Test-Path -LiteralPath $sdkRoot)) {
    throw 'Android SDK was not found. Install Android SDK 36 and set ANDROID_SDK_ROOT.'
}

$task = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
$variant = if ($Release) { 'release' } else { 'debug' }
$apkName = if ($Release) { 'app-release-unsigned.apk' } else { 'app-debug.apk' }

Push-Location $projectDir
try {
    & .\gradlew.bat $task
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }

    $apkPath = Join-Path $projectDir "app\build\outputs\apk\$variant\$apkName"
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "Build finished but the APK was not found: $apkPath"
    }

    Write-Host "APK: $apkPath" -ForegroundColor Green

    if ($Install) {
        $adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
        if (-not (Test-Path -LiteralPath $adb)) {
            throw "adb was not found: $adb"
        }
        & $adb install -r $apkPath
        if ($LASTEXITCODE -ne 0) {
            throw "ADB install failed with exit code $LASTEXITCODE."
        }
    }
}
finally {
    Pop-Location
}
