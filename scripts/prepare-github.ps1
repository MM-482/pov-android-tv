$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'public-source-files.ps1')
$sourceFiles = @(Get-CameoPublicSourceFiles -ProjectRoot $projectRoot)
$buildOutput = (Get-Content -LiteralPath (Join-Path $projectRoot '.tooling\build-output-path.txt') -Raw).Trim()
$metadata = Get-Content -LiteralPath (Join-Path $buildOutput 'outputs\apk\debug\output-metadata.json') -Raw | ConvertFrom-Json
$versionName = $metadata.elements[0].versionName
if ($versionName -notmatch '^\d+\.\d+\.\d+-preview$') { throw 'Unexpected preview version.' }
$version = $versionName.Replace('-preview', '')
$destination = Join-Path $projectRoot "dist\github-ready-$version"
if (Test-Path -LiteralPath $destination) { throw "A prepared bundle already exists at $destination. Review it before preparing another bundle." }
$sourceRoot = Join-Path $destination 'source'
$releaseRoot = Join-Path $destination 'release'
New-Item -ItemType Directory -Path $sourceRoot, $releaseRoot -Force | Out-Null

# Both exporters consume the same fully validated, explicit source set.
foreach ($file in $sourceFiles) {
    $target = Join-Path $sourceRoot $file.RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target
}
foreach ($name in @("Cameo-Android-$version-preview.apk", "Cameo-Android-$version-source.zip", 'SHA256SUMS.txt')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot "dist\$name") -Destination (Join-Path $releaseRoot $name)
}
$manifest = Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | ForEach-Object {
    [pscustomobject]@{ path = [System.IO.Path]::GetRelativePath($sourceRoot, $_.FullName).Replace('\', '/'); bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$manifest | Sort-Object path | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $destination 'source-manifest.json') -Encoding utf8
Copy-Item -LiteralPath (Join-Path $projectRoot "docs\RELEASE_NOTES_$version.md") -Destination (Join-Path $releaseRoot 'RELEASE_NOTES.md')
Write-Output "GitHub source and release bundle prepared: $destination"
Write-Output 'No repository has been created and nothing has been uploaded. Owner approval is required before publication.'
