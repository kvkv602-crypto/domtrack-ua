#Requires -Version 7.0
param([string]$AppRoot=(Join-Path $PSScriptRoot '..\app'),[Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\packaging\signpath\SignPathBuild.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\packaging\msix\StorePackage.psm1')
$app=[IO.Path]::GetFullPath($AppRoot)
$output=New-SignPathDirectory $OutputDirectory
& (Join-Path $PSScriptRoot 'Build-Source.ps1') -AppRoot $app -OutputDirectory (Join-Path $output 'compiled')
$compiled=Join-Path $output 'compiled'
$proof=Get-Content -LiteralPath (Join-Path $compiled 'ci-provenance.json') -Raw | ConvertFrom-Json
if (-not $proof.nativeWindowsHelper -or $proof.guiExecuted -or $proof.signed) { throw 'Unexpected source build evidence.' }
$paths=@(Get-SignPathPayloadPaths)
$payload=New-SignPathDirectory (Join-Path $output 'payload')
foreach($relative in $paths) {
    $destination=Join-Path $payload $relative
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
    if ($relative -ceq 'store-runtime.json') { [IO.File]::WriteAllText($destination,(New-DomTrackStoreRuntimeMarker),[Text.UTF8Encoding]::new($false)); continue }
    Assert-DomTrackPlainFile $compiled $relative
    [IO.File]::Copy((Join-Path $compiled $relative),$destination)
}
Assert-VendorPayload $payload
$inventory=@(Get-PayloadInventory $payload $paths)
$inner=New-SignPathDirectory (Join-Path $output 'inner-signing')
[IO.File]::Copy((Join-Path $payload 'DomTrack-UA.exe'),(Join-Path $inner 'DomTrack-UA.exe'))
Write-SignPathJson (Join-Path $output 'input-evidence.json') ([ordered]@{format='domtrack-signpath-input';version=1;appVersion=$proof.version;commit=$env:GITHUB_SHA;repository=$env:GITHUB_REPOSITORY;inventory=$inventory;source=$proof;nativeOnly=$true;signingRequested=$false})
Write-Output 'Native-only payload and immutable inner-EXE signing input prepared. No application was executed or signed.'
