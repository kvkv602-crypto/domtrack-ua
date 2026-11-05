#Requires -Version 7.0
param([string]$AppRoot=(Join-Path $PSScriptRoot '..\app'),[Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\packaging\signpath\SignPathBuild.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\packaging\signpath\DevelopmentPreview.psm1') -Force
$app=[IO.Path]::GetFullPath($AppRoot)
$output=New-SignPathDirectory $OutputDirectory
$inputRoot=Join-Path $output 'input'
& (Join-Path $PSScriptRoot 'Prepare-SignPathPayload.ps1') -AppRoot $app -OutputDirectory $inputRoot
$inputEvidence=Get-Content -LiteralPath (Join-Path $inputRoot 'input-evidence.json') -Raw | ConvertFrom-Json
$payload=Join-Path $inputRoot 'payload'
$paths=@(Get-SignPathPayloadPaths)
$inventory=@(Get-PayloadInventory $payload $paths)
Assert-PayloadInventory $payload $inputEvidence.inventory
$inner=Join-Path $payload 'DomTrack-UA.exe'
if((Get-AuthenticodeSignature -LiteralPath $inner).Status -ne 'NotSigned'){throw 'This preview builder requires a fresh unsigned owned launcher.'}
$preview=New-SignPathDirectory (Join-Path $output 'preview')
$file=Join-Path $preview (Get-DevelopmentPreviewFileName $inputEvidence.appVersion)
Assert-DevelopmentPreviewFileName $file $inputEvidence.appVersion
$manifest=Join-Path $output 'files.manifest'
$lines=@($inventory | Sort-Object path | ForEach-Object {$_.sha256+"`t"+$_.bytes+"`t"+$_.path})
[IO.File]::WriteAllText($manifest,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$zip=Join-Path $output 'payload.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($payload,$zip,[IO.Compression.CompressionLevel]::Optimal,$false)
$payloadHash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestHash=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
$buildInfo=Join-Path $output 'BuildInfo.cs'
[IO.File]::WriteAllText($buildInfo,('namespace DomTrackSingleFile { internal static class BuildInfo { internal const string PayloadHash = "'+$payloadHash+'"; internal const string ManifestHash = "'+$manifestHash+'"; } }'),[Text.UTF8Encoding]::new($false))
$compiler=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if(-not [IO.File]::Exists($compiler)){throw 'Official .NET Framework compiler was not found.'}
& $compiler /nologo /target:winexe /platform:x64 /optimize+ ('/out:'+$file) ('/win32icon:'+(Join-Path $app 'public\app.ico')) ('/win32manifest:'+(Join-Path $app 'launcher\SingleFile.manifest')) /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.IO.Compression.dll ('/resource:'+$zip+',DomTrack.Payload') ('/resource:'+$manifest+',DomTrack.Manifest') (Join-Path $app 'launcher\SingleFile.cs') $buildInfo
if($LASTEXITCODE -ne 0){throw 'Development outer compilation failed.'}
if((Get-AuthenticodeSignature -LiteralPath $file).Status -ne 'NotSigned'){throw 'Unexpected signature in unsigned development output.'}
$resources=Join-Path $output 'resources-check'
& (Join-Path $app 'launcher\Read-PackageResources.ps1') -Executable $file -Destination $resources
Assert-DevelopmentPreviewResources -ResourcesDirectory $resources -Inventory $inventory -PayloadSha256 $payloadHash -ManifestSha256 $manifestHash
$hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($file+'.sha256'),$hash+'  '+[IO.Path]::GetFileName($file)+"`n",[Text.UTF8Encoding]::new($false))
Write-SignPathJson (Join-Path $preview 'development-evidence.json') ([ordered]@{status='unsigned-development-preview';appVersion=$inputEvidence.appVersion;commit=$env:GITHUB_SHA;repository=$env:GITHUB_REPOSITORY;file=[IO.Path]::GetFileName($file);fileSha256=$hash;fileBytes=(Get-Item -LiteralPath $file).Length;payloadSha256=$payloadHash;manifestSha256=$manifestHash;payloadFiles=$inventory.Count;innerSigned=$false;outerSigned=$false;nativeOnly=$true;appExecuted=$false;guiTested=$false;realDpapiTested=$false;smartAppControlAcceptanceTested=$false;trustedRelease=$false;published=$false;sourceBuild=$inputEvidence.source})
[IO.File]::WriteAllText((Join-Path $preview 'UNSIGNED-DEVELOPMENT.txt'),"UNSIGNED DEVELOPMENT PREVIEW - NOT A TRUSTED WINDOWS RELEASE`nThis single-file build is supplied for source/build review and the SignPath Foundation application. Both owned executables are unsigned. Windows Smart App Control may block them. This build does not remove that block. Do not change protection settings to run it.`nThe payload excludes PowerShell helpers and user data. Native GUI, DPAPI, notifications, clean install, upgrade and enforced-Windows acceptance have not been tested. Only compilation and resource/inventory verification were performed. No signing request or release publication is automatic.`n",[Text.UTF8Encoding]::new($false))
Write-Output ('Unsigned development preview compiled and verified as data: '+[IO.Path]::GetFileName($file))
