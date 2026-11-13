# Shared, fail-closed source policy for local ZIP and GitHub-ready preparation.
# Cloud placeholder tags are allowed because this checkout is in OneDrive.
# Microsoft tag reference: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-fscc/c8e77b37-3909-4fe6-a4ea-2b9d423b1ee4
if (-not ('Pov.PublicSourcePath' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
namespace Pov {
    public static class PublicSourcePath {
        [StructLayout(LayoutKind.Sequential)] private struct TagInfo { public uint Attributes; public uint Tag; }
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
        private static extern SafeFileHandle CreateFileW(string name, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
        [DllImport("kernel32.dll", SetLastError=true)]
        private static extern bool GetFileInformationByHandleEx(SafeFileHandle file, int kind, out TagInfo value, uint size);
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
        private static extern uint GetFinalPathNameByHandleW(SafeFileHandle file, StringBuilder value, uint capacity, uint flags);
        private static SafeFileHandle Open(string path, bool noFollow) {
            var file = CreateFileW(path, 0, 7, IntPtr.Zero, 3, 0x02000000u | (noFollow ? 0x00200000u : 0), IntPtr.Zero);
            if (file.IsInvalid) { file.Dispose(); throw new Win32Exception(Marshal.GetLastWin32Error()); }
            return file;
        }
        public static bool IsCloudPlaceholder(string path) {
            using (var file = Open(path, true)) {
                TagInfo value;
                if (!GetFileInformationByHandleEx(file, 9, out value, 8)) throw new Win32Exception(Marshal.GetLastWin32Error());
                return (value.Tag & 0xffff0fffu) == 0x9000001au;
            }
        }
        public static string Resolved(string path) {
            using (var file = Open(path, false)) {
                var value = new StringBuilder(32768);
                uint count = GetFinalPathNameByHandleW(file, value, (uint)value.Capacity, 0);
                if (count == 0 || count >= value.Capacity) throw new Win32Exception(Marshal.GetLastWin32Error());
                var result = value.ToString();
                if (result.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) return @"\\" + result.Substring(8);
                return result.StartsWith(@"\\?\", StringComparison.Ordinal) ? result.Substring(4) : result;
            }
        }
    }
}
'@
}

function Get-PovPublicSourceFiles {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ProjectRoot)

    $rootPath = [IO.Path]::GetFullPath((Get-Item -LiteralPath $ProjectRoot -Force -ErrorAction Stop).FullName).TrimEnd('\', '/')
    $rootItem = Get-Item -LiteralPath $rootPath -Force -ErrorAction Stop
    if (-not $rootItem.PSIsContainer) { throw 'The source root must be a directory.' }
    if (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -and -not [Pov.PublicSourcePath]::IsCloudPlaceholder($rootPath)) {
        throw 'The source root is an unsupported redirect/reparse point.'
    }
    $physicalRoot = [Pov.PublicSourcePath]::Resolved($rootPath).TrimEnd('\', '/')
    $rootPrefix = $rootPath + [IO.Path]::DirectorySeparatorChar
    $physicalPrefix = $physicalRoot + [IO.Path]::DirectorySeparatorChar
    $checked = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $privateDirectories = @('.git', '.tooling', '.gradle', '.kotlin', '.idea', '__pycache__', 'build', 'generated',
        'captures', 'verification', 'inspection', 'cache', 'caches', 'code_cache', 'shared_prefs', 'databases', 'logs', 'keys', 'credentials')
    $privateNames = @('local.properties', 'gradle-local.properties', 'pov_accounts_encrypted.xml', 'playback.xml', 'pov_appearance.xml',
        'key.properties', 'keystore.properties', 'secrets.properties', 'google-services.json', 'service-account.json', '.npmrc', '.pypirc', '.netrc')
    $privateExtensions = @('.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.log', '.hprof', '.pcap', '.pcapng', '.db', '.sqlite', '.pyc')

    function Assert-PovPublicPath([string]$Candidate) {
        $absolute = [IO.Path]::GetFullPath($Candidate)
        if (-not $absolute.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'A source path escaped the project root.' }
        $relative = [IO.Path]::GetRelativePath($rootPath, $absolute)
        $parts = $relative -split '[\\/]'
        $cursor = $rootPath
        foreach ($part in $parts) {
            if ($part -in @('', '.', '..') -or $part.Contains(':')) { throw 'An invalid relative source path was encountered.' }
            $cursor = Join-Path $cursor $part
            if ($checked.Contains($cursor)) { continue }
            $entry = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
            if ($entry.Name -like '.env*' -or $entry.Name.ToLowerInvariant() -in $privateNames -or
                $entry.Extension.ToLowerInvariant() -in $privateExtensions -or
                ($entry.PSIsContainer -and $entry.Name.ToLowerInvariant() -in $privateDirectories)) {
                throw 'A private or generated file/directory was encountered in the public source allowlist.'
            }
            if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -and -not [Pov.PublicSourcePath]::IsCloudPlaceholder($cursor)) {
                throw 'A symbolic link, junction, or unsupported reparse point was encountered in the public source allowlist.'
            }
            $physical = [Pov.PublicSourcePath]::Resolved($cursor)
            if (-not $physical.StartsWith($physicalPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'A resolved source path escaped the project root.' }
            [void]$checked.Add($cursor)
        }
    }

    $files = [Collections.Generic.List[object]]::new()
    $queue = [Collections.Generic.Queue[string]]::new()
    foreach ($folder in @('app\src', 'scripts', '.github', 'docs', 'design')) {
        $folderPath = Join-Path $rootPath $folder
        Assert-PovPublicPath $folderPath
        if (-not (Get-Item -LiteralPath $folderPath -Force).PSIsContainer) { throw 'A required source directory is not a directory.' }
        $queue.Enqueue($folderPath)
    }
    # Inspect each child before descending. Get-ChildItem -Recurse can traverse
    # redirects before a later filter has a chance to reject their targets.
    while ($queue.Count -gt 0) {
        foreach ($entry in Get-ChildItem -LiteralPath $queue.Dequeue() -Force -ErrorAction Stop) {
            Assert-PovPublicPath $entry.FullName
            if ($entry.PSIsContainer) { $queue.Enqueue($entry.FullName) }
            else { $files.Add([pscustomobject]@{ FullName = $entry.FullName; RelativePath = [IO.Path]::GetRelativePath($rootPath, $entry.FullName).Replace('\', '/'); Length = $entry.Length }) }
        }
    }
    foreach ($relative in @('.gitignore', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'app\build.gradle.kts', 'LICENSE', 'README.md')) {
        $filePath = Join-Path $rootPath $relative
        Assert-PovPublicPath $filePath
        $entry = Get-Item -LiteralPath $filePath -Force -ErrorAction Stop
        if ($entry.PSIsContainer) { throw 'A required source file is a directory.' }
        $files.Add([pscustomobject]@{ FullName = $entry.FullName; RelativePath = [IO.Path]::GetRelativePath($rootPath, $entry.FullName).Replace('\', '/'); Length = $entry.Length })
    }
    return $files.ToArray() | Sort-Object RelativePath
}

function Get-CameoPublicSourceFiles {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ProjectRoot)
    # Validate paths before narrowing the design companions. Archives and unrelated
    # macOS studies are not inputs to the Android build and do not belong in its ZIP.
    Get-PovPublicSourceFiles -ProjectRoot $ProjectRoot | Where-Object {
        $_.RelativePath -notlike 'design/*' -or
        $_.RelativePath.StartsWith('design/concepts-2026-10-26/') -or
        $_.RelativePath.StartsWith('design/cameo-mobile-concepts-2026-11-02/') -or
        $_.RelativePath.StartsWith('design/cameo-android-concepts-2026-11-02/')
    }
}
