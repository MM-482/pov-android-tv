param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tooling'
$gradlePath = Join-Path $toolRoot 'gradle-8.14.3\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradlePath)) { throw 'Run scripts/bootstrap.ps1 first.' }
$env:JAVA_HOME = Join-Path $toolRoot 'jdk-17'
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'Run scripts/bootstrap.ps1 to provision the verified local Java runtime.' }
$env:ANDROID_HOME = Join-Path $toolRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$buildCacheRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'pov-android-build-01a15a6b'
$env:GRADLE_USER_HOME = Join-Path $buildCacheRoot 'gradle-home'
$env:POV_BUILD_OUTPUT = Join-Path $buildCacheRoot 'app-build'
[System.IO.File]::WriteAllText((Join-Path $toolRoot 'build-output-path.txt'), $env:POV_BUILD_OUTPUT)
if ($env:GITHUB_ENV) { [System.IO.File]::AppendAllText($env:GITHUB_ENV, "POV_BUILD_OUTPUT=$env:POV_BUILD_OUTPUT`n") }
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME -Force | Out-Null
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-home'
Remove-Item -LiteralPath Env:\ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
# An unmatched quote in a user PATH entry breaks Java's generated test-worker argfile.
# Normalize only this build process; leave the user's saved Windows PATH untouched.
$buildProcessPath = ($env:PATH -split ';' | ForEach-Object { $_.Replace('"', '').Trim() } | Where-Object { $_ }) -join ';'
$env:PATH = "$env:JAVA_HOME\bin;$buildProcessPath"
Push-Location -LiteralPath $projectRoot
try {
    & $gradlePath @Tasks "-PpovBuildRoot=$env:POV_BUILD_OUTPUT" --console=plain --no-daemon --project-cache-dir (Join-Path $buildCacheRoot 'project-cache')
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with $LASTEXITCODE" }
} finally { Pop-Location }
