$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'public-source-files.ps1')
# Validate the entire explicit set before copying an APK or creating/overwriting a ZIP.
$sourceFiles = @(Get-PovPublicSourceFiles -ProjectRoot $projectRoot)
$buildOutput = (Get-Content -LiteralPath (Join-Path $projectRoot '.tooling\build-output-path.txt') -Raw).Trim()
$apkSource = Join-Path $buildOutput 'outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apkSource)) { throw 'Build the app first.' }
$outputRoot = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$metadata = Get-Content -LiteralPath (Join-Path $buildOutput 'outputs\apk\debug\output-metadata.json') -Raw | ConvertFrom-Json
$versionName = $metadata.elements[0].versionName
if ($versionName -notmatch '^\d+\.\d+\.\d+-preview$') { throw 'Unexpected preview version.' }
$sourceVersion = $versionName.Replace('-preview', '')
$apkOutput = Join-Path $outputRoot "Cameo-Android-$versionName.apk"
Copy-Item -LiteralPath $apkSource -Destination $apkOutput -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceOutput = Join-Path $outputRoot "Cameo-Android-$sourceVersion-source.zip"
$fileStream = [System.IO.File]::Open($sourceOutput, [System.IO.FileMode]::Create)
$archive = [System.IO.Compression.ZipArchive]::new($fileStream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $sourceFiles) {
        $relative = $file.RelativePath
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $relative, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose(); $fileStream.Dispose() }
$checksums = @($apkOutput, $sourceOutput) | ForEach-Object { $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256; '{0}  {1}' -f $hash.Hash.ToLowerInvariant(), [System.IO.Path]::GetFileName($_) }
[System.IO.File]::WriteAllLines((Join-Path $outputRoot 'SHA256SUMS.txt'), $checksums)
Get-ChildItem -LiteralPath $outputRoot -File | Select-Object Name, Length
