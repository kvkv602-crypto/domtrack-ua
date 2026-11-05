#Requires -Version 7.0
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\packaging\signpath\SignPathBuild.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\packaging\signpath\DevelopmentPreview.psm1') -Force
$checks=0
function Assert-That([bool]$Value,[string]$Message){if(-not $Value){throw $Message};$script:checks++}
function Assert-Refused([scriptblock]$Action,[string]$Message){$refused=$false;try{& $Action}catch{$refused=$true};Assert-That $refused $Message}
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('domtrack-preview-contracts-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporary)
try{
    Assert-That ((Get-DevelopmentPreviewFileName '1.3.0') -ceq 'DomTrack-UA-1.3.0-Development-Unsigned.exe') 'Development filename is not explicit.'
    Assert-Refused {Get-DevelopmentPreviewFileName '../../release'} 'Unsafe version accepted.'
    Assert-Refused {Get-DevelopmentPreviewFileName '1.3.0-release'} 'Ambiguous version accepted.'
    Assert-Refused {Get-DevelopmentPreviewFileName '1.65536.0'} 'Out-of-range native version accepted.'
    Assert-Refused {Assert-DevelopmentPreviewFileName 'DomTrack-UA-1.3.0.exe' '1.3.0'} 'Trusted-looking filename accepted.'
    $payload=New-SignPathDirectory (Join-Path $temporary 'payload')
    [IO.File]::WriteAllText((Join-Path $payload 'a.txt'),'fixture alpha')
    [IO.File]::WriteAllText((Join-Path $payload 'b.txt'),'fixture beta')
    $inventory=@(Get-PayloadInventory $payload @('a.txt','b.txt'))
    $resources=New-SignPathDirectory (Join-Path $temporary 'resources')
    $zip=Join-Path $resources 'DomTrack.Payload'
    [IO.Compression.ZipFile]::CreateFromDirectory($payload,$zip)
    $manifest=Join-Path $resources 'DomTrack.Manifest'
    $lines=@($inventory | Sort-Object path | ForEach-Object {$_.sha256+"`t"+$_.bytes+"`t"+$_.path})
    [IO.File]::WriteAllText($manifest,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    $payloadHash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifestHash=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-DevelopmentPreviewResources $resources $inventory $payloadHash $manifestHash;$checks++
    Assert-Refused {Assert-DevelopmentPreviewResources $resources $inventory ('0'*64) $manifestHash} 'Changed resource hash accepted.'
    [IO.File]::AppendAllText($manifest,"unexpected`n")
    $changedManifestHash=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Refused {Assert-DevelopmentPreviewResources $resources $inventory $payloadHash $changedManifestHash} 'Changed manifest accepted even with its updated resource hash.'
    [IO.File]::WriteAllText($manifest,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    $archive=[IO.Compression.ZipFile]::Open($zip,[IO.Compression.ZipArchiveMode]::Update)
    try{$archive.GetEntry('b.txt').Delete();$stream=$archive.CreateEntry('../outside.txt').Open();try{$stream.WriteByte(42)}finally{$stream.Dispose()}}finally{$archive.Dispose()}
    $changedPayloadHash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Refused {Assert-DevelopmentPreviewResources $resources $inventory $changedPayloadHash $manifestHash} 'Unexpected ZIP path accepted.'
    $archive=[IO.Compression.ZipFile]::Open($zip,[IO.Compression.ZipArchiveMode]::Update)
    try{
        $archive.GetEntry('../outside.txt').Delete()
        $stream=$archive.CreateEntry('a.txt').Open();try{$bytes=[Text.Encoding]::UTF8.GetBytes('fixture alpha');$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
    }finally{$archive.Dispose()}
    $changedPayloadHash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Refused {Assert-DevelopmentPreviewResources $resources $inventory $changedPayloadHash $manifestHash} 'Duplicate ZIP path accepted with the expected entry count.'
    $archive=[IO.Compression.ZipFile]::Open($zip,[IO.Compression.ZipArchiveMode]::Update)
    try{
        foreach($entry in @($archive.Entries | Where-Object {$_.FullName -ceq 'a.txt'})){$entry.Delete()}
        $stream=$archive.CreateEntry('b.txt').Open();try{$bytes=[Text.Encoding]::UTF8.GetBytes('fixture beta');$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
        $stream=$archive.CreateEntry('a.txt').Open();try{$bytes=[Text.Encoding]::UTF8.GetBytes('fixture ALPHA');$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
    }finally{$archive.Dispose()}
    $changedPayloadHash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Refused {Assert-DevelopmentPreviewResources $resources $inventory $changedPayloadHash $manifestHash} 'Changed ZIP entry bytes accepted with updated archive hash.'
    Write-Output ("Development preview contract tests passed: $checks. No app or native helper executed.")
}finally{
    $root=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    $resolved=[IO.Path]::GetFullPath($temporary)
    if($resolved.StartsWith($root,[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolved) -like 'domtrack-preview-contracts-*'){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
