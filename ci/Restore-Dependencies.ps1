#Requires -Version 7.0
param([string]$AppRoot = (Join-Path $PSScriptRoot '..\app'), [string]$DownloadDirectory = (Join-Path $PSScriptRoot '..\.build\downloads'))
$ErrorActionPreference = 'Stop'
$taskApp = [IO.Path]::GetFullPath($AppRoot)
if (-not [IO.File]::Exists((Join-Path $taskApp 'server.cjs'))) { throw 'AppRoot must contain the clean app source.' }
$taskLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependency-lock.json') -Raw | ConvertFrom-Json
$taskExistingNode = Join-Path $taskApp 'runtime\node.exe'
if ([IO.File]::Exists($taskExistingNode) -and (Get-FileHash -LiteralPath $taskExistingNode -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskLock.node.exeSha256) { throw 'AppRoot contains another Node build. Use a clean source checkout; local runtime binaries are not CI inputs.' }
$taskDownloads = [IO.Path]::GetFullPath($DownloadDirectory)
[IO.Directory]::CreateDirectory($taskDownloads) | Out-Null

function Get-LockedArchive([string]$Url,[string]$Name,[string]$Algorithm,[string]$Expected,[bool]$Base64 = $false) {
    $taskPath = Join-Path $taskDownloads $Name
    if (-not [IO.File]::Exists($taskPath)) { Invoke-WebRequest -Uri $Url -OutFile $taskPath -TimeoutSec 90 -MaximumRedirection 0 }
    $taskDigest = (Get-FileHash -LiteralPath $taskPath -Algorithm $Algorithm).Hash.ToLowerInvariant()
    if ($Base64) { $taskDigest = [Convert]::ToBase64String([Convert]::FromHexString($taskDigest)) }
    if ($taskDigest -cne $Expected) { throw ('Dependency checksum mismatch: ' + $Name) }
    return $taskPath
}
function Copy-ArchiveFile($Archive,[string]$EntryName,[string]$Target,[long]$Limit) {
    # Entry names and destinations are fixed below; never ExtractToDirectory an untrusted archive.
    $taskEntry = $Archive.GetEntry($EntryName)
    if ($null -eq $taskEntry -or $taskEntry.Length -gt $Limit) { throw ('Missing/oversized dependency entry: ' + $EntryName) }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Target)) | Out-Null
    $taskInput = $taskEntry.Open()
    try { $taskOutput = [IO.File]::Open($Target,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None); try { $taskInput.CopyTo($taskOutput) } finally { $taskOutput.Dispose() } } finally { $taskInput.Dispose() }
}

$taskNodeZip = Get-LockedArchive $taskLock.node.archiveUrl ('node-v'+$taskLock.node.version+'-win-x64.zip') SHA256 $taskLock.node.archiveSha256
$taskSdkZip = Get-LockedArchive $taskLock.webview2.archiveUrl ('microsoft.web.webview2.'+$taskLock.webview2.version+'.nupkg') SHA512 $taskLock.webview2.archiveSha512Base64 $true
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskNodeZip)
try {
    Copy-ArchiveFile $taskArchive ($taskLock.node.archivePrefix+'node.exe') (Join-Path $taskApp 'runtime\node.exe') 256MB
    Copy-ArchiveFile $taskArchive ($taskLock.node.archivePrefix+'LICENSE') (Join-Path $taskApp 'runtime\LICENSE.node.txt') 4MB
} finally { $taskArchive.Dispose() }
if ((Get-FileHash -LiteralPath (Join-Path $taskApp 'runtime\node.exe') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskLock.node.exeSha256) { throw 'Official Node executable hash mismatch.' }
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskSdkZip)
try {
    foreach ($taskPair in @(
        @('lib/net462/Microsoft.Web.WebView2.Core.dll','runtime\webview2\Microsoft.Web.WebView2.Core.dll'),
        @('lib/net462/Microsoft.Web.WebView2.WinForms.dll','runtime\webview2\Microsoft.Web.WebView2.WinForms.dll'),
        @('build/native/x64/WebView2Loader.dll','runtime\webview2\WebView2Loader.dll'),
        @('LICENSE.txt','runtime\webview2\LICENSE.txt'),
        @('NOTICE.txt','runtime\webview2\NOTICE.txt')
    )) { Copy-ArchiveFile $taskArchive $taskPair[0] (Join-Path $taskApp $taskPair[1]) 8MB }
} finally { $taskArchive.Dispose() }
foreach ($taskBinary in @('runtime\node.exe','runtime\webview2\Microsoft.Web.WebView2.Core.dll','runtime\webview2\Microsoft.Web.WebView2.WinForms.dll','runtime\webview2\WebView2Loader.dll')) {
    if ((Get-AuthenticodeSignature -LiteralPath (Join-Path $taskApp $taskBinary)).Status -ne 'Valid') { throw ('Vendor signature did not validate: '+$taskBinary) }
}
$taskNotice = "Official Node.js $($taskLock.node.version) win-x64 from $($taskLock.node.archiveUrl).`nArchive SHA256: $($taskLock.node.archiveSha256)`nnode.exe SHA256: $($taskLock.node.exeSha256)`nLicense extracted from the same verified archive. No local Codex runtime is used.`n"
[IO.File]::WriteAllText((Join-Path $taskApp 'runtime\NOTICE.txt'),$taskNotice,[Text.UTF8Encoding]::new($false))
$taskNotice = "Microsoft.Web.WebView2 $($taskLock.webview2.version), official NuGet package.`n$($taskLock.webview2.archiveUrl)`nSHA512 (Base64): $($taskLock.webview2.archiveSha512Base64)`nOnly net462 Core/WinForms, Windows x64 loader and original notices are extracted. Evergreen WebView2 Runtime is a separate end-user dependency.`n"
[IO.File]::WriteAllText((Join-Path $taskApp 'runtime\webview2\ORIGIN.txt'),$taskNotice,[Text.UTF8Encoding]::new($false))
Write-Output 'Pinned official dependencies restored and vendor signatures verified; no downloaded binary was executed by this script.'
