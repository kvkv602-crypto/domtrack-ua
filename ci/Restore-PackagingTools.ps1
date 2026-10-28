#Requires -Version 7.0
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\.build\windows-sdk'),
    [string]$DownloadDirectory = (Join-Path $PSScriptRoot '..\.build\downloads')
)
$ErrorActionPreference = 'Stop'
$taskLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependency-lock.json') -Raw | ConvertFrom-Json
$taskSdk = $taskLock.packagingTools
if ($taskSdk.version -cne '10.0.28000.2705' -or $taskSdk.archivePrefix -cne 'bin/10.0.28000.0/x64/') { throw 'Unreviewed packaging tool version.' }
$taskDestination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskDestination) { throw 'OutputDirectory must be new. Existing SDK directories are never overwritten or reused.' }
$taskDownloads = [IO.Path]::GetFullPath($DownloadDirectory)
[IO.Directory]::CreateDirectory($taskDownloads) | Out-Null
$taskArchiveFile = Join-Path $taskDownloads ('microsoft.windows.sdk.buildtools.'+$taskSdk.version+'.nupkg')
if (-not [IO.File]::Exists($taskArchiveFile)) { Invoke-WebRequest -Uri $taskSdk.archiveUrl -OutFile $taskArchiveFile -TimeoutSec 90 -MaximumRedirection 0 }
$taskHash = [Convert]::ToBase64String([Convert]::FromHexString((Get-FileHash -LiteralPath $taskArchiveFile -Algorithm SHA512).Hash))
if ($taskHash -cne $taskSdk.archiveSha512Base64) { throw 'SDK checksum mismatch.' }
# Preserve SDK-relative paths. No installer, registry modification or certificate import.
$taskFiles = @(
    'makeappx.exe','appxpackaging.dll','appxsip.dll','opcservices.dll','wintrust.dll','mssign32.dll','msisip.dll','ServicingCommon.dll',
    'Microsoft.Windows.Build.Appx.AppxPackaging.dll.manifest','Microsoft.Windows.Build.Appx.AppxSip.dll.manifest',
    'Microsoft.Windows.Build.Appx.OpcServices.dll.manifest','Microsoft.Windows.Build.Signing.mssign32.dll.manifest',
    'Microsoft.Windows.Build.Signing.wintrust.dll.manifest','en-US/AppxPackaging.dll.mui'
) | ForEach-Object { $taskSdk.archivePrefix + $_ }
$taskSchemas = @('AppxManifestSchema','AppxManifestSchema2010_v2','AppxManifestSchema2013','FoundationManifestSchema','FoundationManifestSchema_v2','UapManifestSchema','UapManifestSchema_v2','UapManifestSchema_v3','UapManifestSchema_v4','UapManifestSchema_v5','UapManifestSchema_v6','UapManifestSchema_v7','UapManifestSchema_v8','UapManifestSchema_v10','UapManifestSchema_v11','UapManifestSchema_v12','UapManifestSchema_v13','UapManifestSchema_v15','UapManifestSchema_v15a','UapManifestSchema_v16','UapManifestSchema_v17','UapManifestSchema_v18')
$taskFiles += $taskSchemas | ForEach-Object { 'schemas/10.0.28000.0/winrt/'+$_+'.xsd' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskArchiveFile)
try {
    foreach ($taskRelative in $taskFiles) {
        $taskEntry = $taskArchive.GetEntry($taskRelative)
        if ($null -eq $taskEntry -or $taskEntry.Length -gt 16MB) { throw ('Missing or oversized SDK component: '+$taskRelative) }
        $taskTarget = Join-Path $taskDestination $taskRelative.Replace('/',[IO.Path]::DirectorySeparatorChar)
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskTarget)) | Out-Null
        $taskInput = $taskEntry.Open()
        try { $taskOutput = [IO.File]::Open($taskTarget,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None); try { $taskInput.CopyTo($taskOutput) } finally { $taskOutput.Dispose() } } finally { $taskInput.Dispose() }
        if ([IO.Path]::GetExtension($taskTarget) -in @('.exe','.dll')) {
            $taskSignature = Get-AuthenticodeSignature -LiteralPath $taskTarget
            if ($taskSignature.Status -ne 'Valid' -or $taskSignature.SignerCertificate.Subject -notmatch '(^|,\s*)O=Microsoft Corporation(,|$)') { throw ('Microsoft SDK signature failed: '+$taskRelative) }
        }
    }
} finally { $taskArchive.Dispose() }
$taskResult = [ordered]@{MakeAppxPath=(Join-Path $taskDestination ($taskSdk.archivePrefix+'makeappx.exe'));Version=$taskSdk.version;ArchiveSHA512=$taskHash;Executed=$false}
[IO.File]::WriteAllText((Join-Path $taskDestination 'restore-provenance.json'),($taskResult | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
[pscustomobject]$taskResult
