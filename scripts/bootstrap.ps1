$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tooling'
$downloadRoot = Join-Path $toolRoot 'downloads'
New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
function Get-VerifiedFile([string]$Url, [string]$Path, [string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Path)) { Invoke-WebRequest -Uri $Url -OutFile $Path }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Sha256) { throw "Checksum mismatch: $Path" }
}
$sdkZip = Join-Path $downloadRoot 'android-commandline-tools.zip'
Get-VerifiedFile 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' $sdkZip '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$gradleZip = Join-Path $downloadRoot 'gradle-8.14.3-bin.zip'
Get-VerifiedFile 'https://services.gradle.org/distributions/gradle-8.14.3-bin.zip' $gradleZip 'bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$jdkRoot = Join-Path $toolRoot 'jdk-17'
if (-not (Test-Path -LiteralPath (Join-Path $jdkRoot 'bin\java.exe'))) {
    $jdkZip = Join-Path $downloadRoot 'temurin-17.0.20.1.zip'
    Get-VerifiedFile 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' $jdkZip 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
    $jdkUnpacked = Join-Path $toolRoot 'jdk17-unpacked'
    if (-not (Test-Path -LiteralPath $jdkUnpacked)) { Expand-Archive -LiteralPath $jdkZip -DestinationPath $jdkUnpacked }
    Copy-Item -LiteralPath (Join-Path $jdkUnpacked 'jdk-17.0.20.1+1') -Destination $jdkRoot -Recurse
}
$cmdlineRoot = Join-Path $sdkRoot 'cmdline-tools'
if (-not (Test-Path -LiteralPath (Join-Path $cmdlineRoot 'latest\bin\sdkmanager.bat'))) {
    New-Item -ItemType Directory -Path $cmdlineRoot -Force | Out-Null
    $unpackRoot = Join-Path $toolRoot 'sdk-unpacked'
    Expand-Archive -LiteralPath $sdkZip -DestinationPath $unpackRoot
    Copy-Item -LiteralPath (Join-Path $unpackRoot 'cmdline-tools') -Destination (Join-Path $cmdlineRoot 'latest') -Recurse
}
if (-not (Test-Path -LiteralPath (Join-Path $toolRoot 'gradle-8.14.3'))) {
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $toolRoot
}
$env:JAVA_HOME = $jdkRoot
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-home'
Remove-Item -LiteralPath Env:\ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
$sdkManager = Join-Path $cmdlineRoot 'latest\bin\sdkmanager.bat'
1..30 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses
if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed.' }
& $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK component setup failed.' }
$sdkPropertyPath = $sdkRoot.Replace('\', '/').Replace(':', '\:')
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=$sdkPropertyPath`n", [System.Text.UTF8Encoding]::new($false))
Write-Output 'Verified project-local Android SDK and Gradle are ready.'
