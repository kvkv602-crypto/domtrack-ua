#Requires -Version 7.0
param([Parameter(Mandatory)][string]$InputDirectory,[Parameter(Mandatory)][string]$SignedLauncherDirectory,[Parameter(Mandatory)][string]$OutputDirectory,[Parameter(Mandatory)][string]$ExpectedSignerThumbprint)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\packaging\signpath\SignPathBuild.psm1') -Force
Assert-SignerThumbprint $ExpectedSignerThumbprint
$inputRoot=[IO.Path]::GetFullPath($InputDirectory)
$sourcePayload=Join-Path $inputRoot 'payload'
$evidence=Get-Content -LiteralPath (Join-Path $inputRoot 'input-evidence.json') -Raw | ConvertFrom-Json
if($evidence.format -cne 'domtrack-signpath-input' -or $evidence.version -ne 1 -or -not $evidence.nativeOnly) { throw 'Invalid signing input evidence.' }
$expected=@(Get-SignPathPayloadPaths)
if ((@($evidence.inventory.path | Sort-Object) -join "`n") -cne (@($expected | Sort-Object) -join "`n")) { throw 'Input inventory differs from the reviewed payload allowlist.' }
Assert-PayloadInventory $sourcePayload $evidence.inventory
$null=Get-PayloadInventory $SignedLauncherDirectory @('DomTrack-UA.exe')
$signed=Join-Path ([IO.Path]::GetFullPath($SignedLauncherDirectory)) 'DomTrack-UA.exe'
Assert-SignedPeContent (Join-Path $sourcePayload 'DomTrack-UA.exe') $signed
$innerSignature=Assert-OwnedSignature $signed $ExpectedSignerThumbprint
$output=New-SignPathDirectory $OutputDirectory
$payload=New-SignPathDirectory (Join-Path $output 'payload')
foreach($relative in $expected) {
    $destination=Join-Path $payload $relative
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
    $source=if($relative -ceq 'DomTrack-UA.exe'){$signed}else{Join-Path $sourcePayload $relative}
    [IO.File]::Copy($source,$destination)
}
Assert-VendorPayload $payload
$inventory=@(Get-PayloadInventory $payload $expected)
$manifest=Join-Path $output 'files.manifest'
$lines=@($inventory | ForEach-Object { $_.sha256+"`t"+$_.bytes+"`t"+$_.path })
[IO.File]::WriteAllText($manifest,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$zip=Join-Path $output 'payload.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($payload,$zip,[IO.Compression.CompressionLevel]::Optimal,$false)
$payloadHash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestHash=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
$buildInfo=Join-Path $output 'BuildInfo.cs'
[IO.File]::WriteAllText($buildInfo,('namespace DomTrackSingleFile { internal static class BuildInfo { internal const string PayloadHash = "'+$payloadHash+'"; internal const string ManifestHash = "'+$manifestHash+'"; } }'),[Text.UTF8Encoding]::new($false))
$app=Join-Path $PSScriptRoot '..\app'
$compiler=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if(-not [IO.File]::Exists($compiler)){throw 'Official .NET Framework compiler was not found.'}
$outer=New-SignPathDirectory (Join-Path $output 'outer-signing')
$outerFile=Join-Path $outer 'DomTrack-UA.exe'
& $compiler /nologo /target:winexe /platform:x64 /optimize+ ('/out:'+$outerFile) ('/win32icon:'+(Join-Path $app 'public\app.ico')) ('/win32manifest:'+(Join-Path $app 'launcher\SingleFile.manifest')) /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.IO.Compression.dll ('/resource:'+$zip+',DomTrack.Payload') ('/resource:'+$manifest+',DomTrack.Manifest') (Join-Path $app 'launcher\SingleFile.cs') $buildInfo
if($LASTEXITCODE -ne 0){throw 'Outer package compilation failed.'}
& (Join-Path $app 'launcher\Read-PackageResources.ps1') -Executable $outerFile -Destination (Join-Path $output 'resources-check')
foreach($pair in @(@('DomTrack.Payload',$payloadHash),@('DomTrack.Manifest',$manifestHash))){if((Get-FileHash -LiteralPath (Join-Path $output ('resources-check\'+$pair[0])) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $pair[1]){throw 'Embedded resource bytes differ from the sealed payload.'}}
Write-SignPathJson (Join-Path $output 'sealed-evidence.json') ([ordered]@{format='domtrack-signpath-sealed';version=1;appVersion=$evidence.appVersion;commit=$evidence.commit;repository=$evidence.repository;inventory=$inventory;innerSignature=$innerSignature;payloadSha256=$payloadHash;manifestSha256=$manifestHash;unsignedOuterSha256=(Get-FileHash -LiteralPath $outerFile -Algorithm SHA256).Hash.ToLowerInvariant();appExecuted=$false;outerSigned=$false})
Write-Output 'Signed inner payload sealed and outer signing input compiled. Resources were read as data; no app executed.'
