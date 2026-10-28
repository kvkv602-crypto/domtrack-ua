#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$IdentityName,
    [string]$Publisher,
    [string]$PublisherDisplayName,
    [string]$SourceBuildDirectory = (Join-Path $PSScriptRoot '..\dist\unsigned'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\dist\store'),
    [string]$MakeAppxPath,
    [switch]$ValidateIdentityOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot '..\packaging\msix\StorePackage.psm1') -Force

# The identity gate precedes all filesystem mutations, dependency restore and SDK invocation.
Assert-DomTrackStoreIdentity -IdentityName $IdentityName -Publisher $Publisher -PublisherDisplayName $PublisherDisplayName
if ($ValidateIdentityOnly) { Write-Output 'Partner Center identity strings passed local syntax checks; their ownership/association still requires Partner Center validation.'; return }

$taskSource = [IO.Path]::GetFullPath($SourceBuildDirectory).TrimEnd('\', '/')
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\', '/')
if ([IO.Directory]::Exists($taskOutput) -or [IO.File]::Exists($taskOutput)) { throw 'OutputDirectory must be new. This script never overwrites or deletes an existing package.' }
if ($taskOutput -eq $taskSource -or $taskOutput.StartsWith($taskSource + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $taskSource.StartsWith($taskOutput + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source and output directories must be separate.' }
$taskPayload = @(Get-DomTrackMsixPayload)
foreach ($taskRelative in $taskPayload) { Assert-DomTrackPlainFile -Root $taskSource -RelativePath $taskRelative }
$taskPackage = Get-Content -LiteralPath (Join-Path $taskSource 'package.json') -Raw | ConvertFrom-Json
if ($taskPackage.name -cne 'domtrack-ua' -or $taskPackage.version -cnotmatch '^[1-9][0-9]*\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') { throw 'Expected a clean DomTrack source build with a three-component numeric release version.' }
$taskVersion = $taskPackage.version + '.0'
Assert-DomTrackStoreVersion $taskVersion
$taskManifest = New-DomTrackStoreManifest -IdentityName $IdentityName -Publisher $Publisher -PublisherDisplayName $PublisherDisplayName -Version $taskVersion

# No source secret, database, log, development helper, PowerShell script, SFX or signing material is selected.
# The shared selector may support a separate unpackaged development edition. Store mode must require the native bridge.
$taskSelector = [IO.File]::ReadAllText((Join-Path $taskSource 'lib/windows-helper.cjs'))
if (-not $taskSelector.Contains('store-runtime.json') -or -not $taskSelector.Contains('domtrack-store-runtime')) { throw 'The Windows helper selector does not recognize the required Store runtime marker.' }
Assert-DomTrackPlainFile -Root $taskSource -RelativePath 'ci-provenance.json'
$taskSourceEvidence = Get-Content -LiteralPath (Join-Path $taskSource 'ci-provenance.json') -Raw | ConvertFrom-Json
if ($taskSourceEvidence.PSObject.Properties.Name -notcontains 'nativeWindowsHelper' -or $taskSourceEvidence.nativeWindowsHelper -ne $true) { throw 'Source build evidence must confirm that the native Windows helper was compiled into the launcher.' }
$taskExe = Get-Item -LiteralPath (Join-Path $taskSource 'DomTrack-UA.exe')
if ($taskExe.VersionInfo.FileVersion -cne $taskVersion) { throw 'Launcher and Store package versions disagree.' }
$taskLauncherDigest = (Get-FileHash -LiteralPath $taskExe.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($taskSourceEvidence.PSObject.Properties.Name -notcontains 'nativeLauncherSha256' -or $taskSourceEvidence.nativeLauncherSha256 -cnotmatch '^[0-9a-f]{64}$' -or $taskSourceEvidence.nativeLauncherSha256 -cne $taskLauncherDigest -or $taskSourceEvidence.version -cne $taskPackage.version) { throw 'Native helper build evidence version/hash does not match the actual launcher payload.' }
$taskLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependency-lock.json') -Raw | ConvertFrom-Json
if ((Get-FileHash -LiteralPath (Join-Path $taskSource 'runtime/node.exe') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskLock.node.exeSha256) { throw 'The payload does not contain the pinned official Node build.' }
foreach ($taskVendor in @('runtime/node.exe', 'runtime/webview2/Microsoft.Web.WebView2.Core.dll', 'runtime/webview2/Microsoft.Web.WebView2.WinForms.dll', 'runtime/webview2/WebView2Loader.dll')) {
    if ((Get-AuthenticodeSignature -LiteralPath (Join-Path $taskSource $taskVendor)).Status -ne 'Valid') { throw ('Vendor signature did not validate: ' + $taskVendor) }
}
$taskTool = Resolve-DomTrackMakeAppx -MakeAppxPath $MakeAppxPath

# All required inputs have been checked before the first write.
$taskStage = Join-Path $taskOutput 'payload'
[IO.Directory]::CreateDirectory($taskStage) | Out-Null
foreach ($taskRelative in $taskPayload) {
    $taskDestination = Join-Path $taskStage $taskRelative
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskDestination)) | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskSource $taskRelative) -Destination $taskDestination
}
[IO.File]::WriteAllText((Join-Path $taskStage 'store-runtime.json'), (New-DomTrackStoreRuntimeMarker), [Text.UTF8Encoding]::new($false))
New-DomTrackMsixAssets -IconPath (Join-Path $taskSource 'public/app.ico') -OutputDirectory (Join-Path $taskStage 'Assets')
$taskWriterSettings = [Xml.XmlWriterSettings]::new()
$taskWriterSettings.Encoding = [Text.UTF8Encoding]::new($false)
$taskWriterSettings.Indent = $true
$taskWriter = [Xml.XmlWriter]::Create((Join-Path $taskStage 'AppxManifest.xml'), $taskWriterSettings)
try { $taskManifest.Save($taskWriter) } finally { $taskWriter.Dispose() }

$taskPackagePath = Join-Path $taskOutput ('DomTrack-UA_' + $taskVersion + '_x64.msix')
if ((Get-FileHash -LiteralPath $taskTool.Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskTool.Sha256) { throw 'SDK tool changed after signature validation.' }
# MakeAppx is the only executable this packaging script invokes. Validation remains enabled (no /nv).
& $taskTool.Path pack /d $taskStage /p $taskPackagePath /h SHA256
if ($LASTEXITCODE -ne 0 -or -not [IO.File]::Exists($taskPackagePath)) { throw 'MakeAppx packaging/schema validation failed. No package is ready for submission.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskPackagePath)
try {
    $taskActual = @($taskArchive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    if ($taskActual -contains 'AppxSignature.p7x') { throw 'This preparation path must produce an unsigned Store submission package.' }
    $taskExpected = @($taskPayload) + @('store-runtime.json', 'Assets/StoreLogo.png', 'Assets/Square44x44Logo.png', 'Assets/Square150x150Logo.png', 'AppxManifest.xml', 'AppxBlockMap.xml', '[Content_Types].xml')
    if (@(Compare-Object ($taskExpected | Sort-Object) ($taskActual | Sort-Object)).Count -ne 0) { throw 'The MSIX entries differ from the reviewed allowlist and generated package metadata.' }
} finally { $taskArchive.Dispose() }
$taskDigest = (Get-FileHash -LiteralPath $taskPackagePath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($taskPackagePath + '.sha256'), $taskDigest + '  ' + [IO.Path]::GetFileName($taskPackagePath) + "`n", [Text.UTF8Encoding]::new($false))
$taskEvidence = [ordered]@{ status = 'unsigned-msix-for-store-submission-only'; applicationExecuted = $false; packageInstalled = $false; packageSigned = $false; submitted = $false; storeCertified = $false; identityOwnershipVerified = $false; identityName = $IdentityName; publisher = $Publisher; publisherDisplayName = $PublisherDisplayName; version = $taskVersion; architecture = 'x64'; commit = $env:GITHUB_SHA; makeAppx = $taskTool; nativeWindowsHelper = $true; nativeLauncherSha256 = $taskLauncherDigest; storeRuntimeMarkerVersion = 1; payloadFileCount = $taskPayload.Count; packageSha256 = $taskDigest; pending = @('Partner Center identity association and restricted capability review', 'Store certification and Microsoft signature', 'Clean-device installed-package tests with Smart App Control enabled', 'WebView2 Evergreen Runtime prerequisite and migration acceptance') }
[IO.File]::WriteAllText((Join-Path $taskOutput 'store-package-evidence.json'), ($taskEvidence | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $taskOutput 'SUBMISSION-ONLY.txt'), "Unsigned MSIX prepared for Microsoft Store submission only. It was not installed, executed, signed, submitted or certified. Do not distribute it as a trusted release. Microsoft Store certification/signing and installed-package acceptance remain pending.`n", [Text.UTF8Encoding]::new($false))
Write-Output ('Unsigned MSIX prepared for Partner Center review: ' + $taskPackagePath)
