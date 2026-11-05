#Requires -Version 7.0
param([Parameter(Mandatory)][string]$SealedDirectory,[Parameter(Mandatory)][string]$SignedOuterDirectory,[Parameter(Mandatory)][string]$OutputDirectory,[Parameter(Mandatory)][string]$ExpectedSignerThumbprint)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\packaging\signpath\SignPathBuild.psm1') -Force
Assert-SignerThumbprint $ExpectedSignerThumbprint
$sealed=[IO.Path]::GetFullPath($SealedDirectory)
$evidence=Get-Content -LiteralPath (Join-Path $sealed 'sealed-evidence.json') -Raw | ConvertFrom-Json
if($evidence.format -cne 'domtrack-signpath-sealed' -or $evidence.version -ne 1 -or $evidence.outerSigned -or $evidence.appExecuted){throw 'Unexpected sealed evidence.'}
$expected=@(Get-SignPathPayloadPaths)
if((@($evidence.inventory.path | Sort-Object) -join "`n") -cne (@($expected | Sort-Object) -join "`n")){throw 'Sealed payload allowlist changed.'}
Assert-PayloadInventory (Join-Path $sealed 'payload') $evidence.inventory
Assert-VendorPayload (Join-Path $sealed 'payload')
$innerSignature=Assert-OwnedSignature (Join-Path $sealed 'payload\DomTrack-UA.exe') $ExpectedSignerThumbprint
$null=Get-PayloadInventory $SignedOuterDirectory @('DomTrack-UA.exe')
$signed=Join-Path ([IO.Path]::GetFullPath($SignedOuterDirectory)) 'DomTrack-UA.exe'
$unsigned=Join-Path $sealed 'outer-signing\DomTrack-UA.exe'
if((Get-FileHash -LiteralPath $unsigned -Algorithm SHA256).Hash.ToLowerInvariant() -cne $evidence.unsignedOuterSha256){throw 'Unsigned outer baseline was modified.'}
Assert-SignedPeContent $unsigned $signed
$outerSignature=Assert-OwnedSignature $signed $ExpectedSignerThumbprint
$output=New-SignPathDirectory $OutputDirectory
& (Join-Path $PSScriptRoot '..\app\launcher\Read-PackageResources.ps1') -Executable $signed -Destination (Join-Path $output 'verified-resources')
foreach($pair in @(@('DomTrack.Payload',$evidence.payloadSha256),@('DomTrack.Manifest',$evidence.manifestSha256))){if((Get-FileHash -LiteralPath (Join-Path $output ('verified-resources\'+$pair[0])) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $pair[1]){throw 'Signed outer contains changed payload resources.'}}
$file=Join-Path $output ('DomTrack-UA-'+$evidence.appVersion+'.exe')
[IO.File]::Copy($signed,$file)
$sha=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($file+'.sha256'),$sha+'  '+[IO.Path]::GetFileName($file)+"`n",[Text.UTF8Encoding]::new($false))
Write-SignPathJson (Join-Path $output 'signing-evidence.json') ([ordered]@{status='signed-candidate-awaiting-runtime-acceptance';appVersion=$evidence.appVersion;commit=$evidence.commit;repository=$evidence.repository;fileSha256=$sha;innerSignature=$innerSignature;outerSignature=$outerSignature;payloadSha256=$evidence.payloadSha256;manifestSha256=$evidence.manifestSha256;payloadFiles=$evidence.inventory.Count;guiTested=$false;realDpapiTested=$false;smartAppControlAcceptanceTested=$false;published=$false})
[IO.File]::WriteAllText((Join-Path $output 'ACCEPTANCE-REQUIRED.txt'),"Signed candidate only. Native GUI, clean install, upgrade, DPAPI, notifications and Smart App Control acceptance remain required before a release claim. This job never runs the app or publishes a release.`n",[Text.UTF8Encoding]::new($false))
Write-Output 'Both signatures and exact embedded resources verified. Signed candidate retained for runtime acceptance; not published.'
