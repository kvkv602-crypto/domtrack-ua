#Requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'StorePackage.psm1') -Force
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskChecks = 0
function Assert-Check([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; $script:taskChecks++ }
function Assert-Rejected([scriptblock]$Operation, [string]$Message) { $taskRejected = $false; try { & $Operation } catch { $taskRejected = $true }; Assert-Check $taskRejected $Message }

foreach ($taskScript in @((Join-Path $PSScriptRoot 'StorePackage.psm1'), (Join-Path $PSScriptRoot 'Test-StorePackaging.ps1'), (Join-Path $taskRepository 'ci/Build-StorePackage.ps1'))) {
    $taskTokens = $null; $taskErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($taskScript, [ref]$taskTokens, [ref]$taskErrors)
    Assert-Check ($taskErrors.Count -eq 0) ('PowerShell parse error: ' + $taskScript)
}
# These identities exist only in test memory; they are never build defaults or submitted.
$taskIdentity = @{ IdentityName = 'Tests.LocalStructureOnly'; Publisher = 'CN=LocalStructureTest'; PublisherDisplayName = 'Local Structure Test & QA' }
foreach ($taskMissing in @('IdentityName', 'Publisher', 'PublisherDisplayName')) {
    $taskArgs = $taskIdentity.Clone(); $taskArgs[$taskMissing] = ''
    Assert-Rejected { Assert-DomTrackStoreIdentity @taskArgs } ('Missing identity field accepted: ' + $taskMissing)
}
Assert-Rejected { Assert-DomTrackStoreIdentity -IdentityName '../unsafe' -Publisher $taskIdentity.Publisher -PublisherDisplayName 'Test' } 'Unsafe package name accepted.'
Assert-Rejected { Assert-DomTrackStoreIdentity -IdentityName $taskIdentity.IdentityName -Publisher 'not-a-distinguished-name' -PublisherDisplayName 'Test' } 'Invalid publisher accepted.'
Assert-Rejected { Assert-DomTrackStoreIdentity -IdentityName '__PARTNER_CENTER_IDENTITY_NAME__' -Publisher $taskIdentity.Publisher -PublisherDisplayName 'Test' } 'Identity placeholder accepted.'
foreach ($taskBadVersion in @('0.1.0.0', '1.2.3.4', '1.65536.0.0', '1.2.0', '01.2.0.0')) { Assert-Rejected { Assert-DomTrackStoreVersion $taskBadVersion } ('Invalid Store version accepted: ' + $taskBadVersion) }

$taskManifest = New-DomTrackStoreManifest @taskIdentity -Version '1.2.0.0'
$taskNs = [Xml.XmlNamespaceManager]::new($taskManifest.NameTable)
$taskNs.AddNamespace('f', 'http://schemas.microsoft.com/appx/manifest/foundation/windows10')
$taskNs.AddNamespace('uap', 'http://schemas.microsoft.com/appx/manifest/uap/windows10')
$taskNs.AddNamespace('uap10', 'http://schemas.microsoft.com/appx/manifest/uap/windows10/10')
$taskNs.AddNamespace('rescap', 'http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities')
$taskId = $taskManifest.SelectSingleNode('/f:Package/f:Identity', $taskNs)
Assert-Check ($taskId.GetAttribute('Name') -ceq $taskIdentity.IdentityName -and $taskId.GetAttribute('Publisher') -ceq $taskIdentity.Publisher -and $taskId.GetAttribute('ProcessorArchitecture') -ceq 'x64') 'Manifest does not preserve exact identity/x64.'
Assert-Check ($taskManifest.SelectSingleNode('/f:Package/f:Properties/f:PublisherDisplayName', $taskNs).InnerText -ceq $taskIdentity.PublisherDisplayName) 'PublisherDisplayName was not safely XML-encoded/preserved.'
$taskApp = $taskManifest.SelectSingleNode('/f:Package/f:Applications/f:Application', $taskNs)
Assert-Check ($taskApp.GetAttribute('Executable') -ceq 'DomTrack-UA.exe' -and $taskApp.GetAttribute('EntryPoint') -ceq 'Windows.FullTrustApplication' -and $taskApp.GetAttribute('TrustLevel', $taskNs.LookupNamespace('uap10')) -ceq 'mediumIL' -and $taskApp.GetAttribute('RuntimeBehavior', $taskNs.LookupNamespace('uap10')) -ceq 'packagedClassicApp') 'Desktop full-trust entry point changed.'
Assert-Check ($null -ne $taskManifest.SelectSingleNode('/f:Package/f:Capabilities/rescap:Capability[@Name="runFullTrust"]', $taskNs)) 'Missing desktop runFullTrust declaration.'
Assert-Check ($null -eq $taskManifest.SelectSingleNode('//f:Extensions', $taskNs)) 'Unexpected autostart/service/extension declarations.'
$taskPayload = @(Get-DomTrackMsixPayload)
Assert-Check (($taskPayload -contains 'DomTrack-UA.exe') -and ($taskPayload -contains 'runtime/node.exe')) 'Required packaged executables missing.'
Assert-Check (@($taskPayload | Where-Object { $_ -match '\.(ps1|cs|pfx|sqlite|zip)$|(^|/)(tests?|data|launcher)/' }).Count -eq 0) 'Private or development payload entries selected.'
Assert-Check (@($taskPayload | Where-Object { $_ -match '\.exe$' }).Count -eq 2) 'Unexpected extra executable selected.'
$taskMarker = New-DomTrackStoreRuntimeMarker | ConvertFrom-Json
Assert-Check ($taskMarker.format -ceq 'domtrack-store-runtime' -and $taskMarker.version -eq 1 -and @($taskMarker.PSObject.Properties).Count -eq 2) 'Store runtime marker does not match the selector contract.'

$taskTemporary = Join-Path ([IO.Path]::GetTempPath()) ('domtrack-msix-structure-' + [Guid]::NewGuid().ToString('N'))
$taskOutput = Join-Path $taskTemporary 'must-not-be-created'
try {
    Assert-Rejected { & (Join-Path $taskRepository 'ci/Build-StorePackage.ps1') -OutputDirectory $taskOutput } 'A package build without identity did not fail.'
    Assert-Check (-not [IO.Directory]::Exists($taskTemporary)) 'Identity failure wrote files before refusing.'
    & (Join-Path $taskRepository 'ci/Build-StorePackage.ps1') @taskIdentity -OutputDirectory $taskOutput -ValidateIdentityOnly | Out-Null
    Assert-Check (-not [IO.Directory]::Exists($taskTemporary)) 'Identity-only validation wrote files.'
    [IO.Directory]::CreateDirectory($taskTemporary) | Out-Null
    [IO.File]::WriteAllText((Join-Path $taskTemporary 'plain.txt'), 'fixture')
    Assert-DomTrackPlainFile -Root $taskTemporary -RelativePath 'plain.txt'
    Assert-Rejected { Assert-DomTrackPlainFile -Root $taskTemporary -RelativePath '../not-inside.txt' } 'Outside payload path accepted.'
    Assert-Rejected { Assert-DomTrackPlainFile -Root $taskTemporary -RelativePath 'missing.txt' } 'Missing payload file accepted.'
    $taskIcon = Join-Path $taskRepository 'app/public/app.ico'
    New-DomTrackMsixAssets -IconPath $taskIcon -OutputDirectory (Join-Path $taskTemporary 'assets-a')
    New-DomTrackMsixAssets -IconPath $taskIcon -OutputDirectory (Join-Path $taskTemporary 'assets-b')
    foreach ($taskAsset in @(@('StoreLogo.png', 50), @('Square44x44Logo.png', 44), @('Square150x150Logo.png', 150))) {
        $taskPathA = Join-Path $taskTemporary ('assets-a/' + $taskAsset[0]); $taskPathB = Join-Path $taskTemporary ('assets-b/' + $taskAsset[0])
        Assert-Check ((Get-FileHash -LiteralPath $taskPathA).Hash -ceq (Get-FileHash -LiteralPath $taskPathB).Hash) ('Asset is not deterministic: ' + $taskAsset[0])
        $taskImage = [Drawing.Image]::FromFile($taskPathA)
        try { Assert-Check ($taskImage.Width -eq $taskAsset[1] -and $taskImage.Height -eq $taskAsset[1]) ('Wrong asset dimensions: ' + $taskAsset[0]) } finally { $taskImage.Dispose() }
    }
} finally {
    # Delete only this known, absolute, GUID-named test directory under the system temp root.
    $taskTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $taskResolved = [IO.Path]::GetFullPath($taskTemporary)
    if (-not $taskResolved.StartsWith($taskTempRoot, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskResolved) -notmatch '^domtrack-msix-structure-[0-9a-f]{32}$') { throw 'Unsafe test cleanup path.' }
    if ([IO.Directory]::Exists($taskResolved)) { Remove-Item -LiteralPath $taskResolved -Recurse -Force }
}
Write-Output ("Store packaging structure checks passed: $taskChecks. No app/bridge/SDK executable was invoked, installed or signed.")
