#Requires -Version 7.0
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\packaging\signpath\SignPathBuild.psm1') -Force
$checks=0
function Assert-That([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:checks++}
function Assert-Refused([scriptblock]$Action,[string]$Message){$refused=$false;try{& $Action}catch{$refused=$true};Assert-That $refused $Message}
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('domtrack-signpath-contracts-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporary)
try {
    $settings=@{Enabled='true';OrganizationId='11111111-2222-3333-4444-555555555555';ProjectSlug='fixture-project';PolicySlug='fixture-policy';InnerConfiguration='fixture-inner';OuterConfiguration='fixture-outer';SignerThumbprint='1234567890ABCDEF1234567890ABCDEF12345678';TokenPresent=$true}
    Assert-SignPathSettings @settings; $checks++
    foreach($field in @('Enabled','OrganizationId','ProjectSlug','PolicySlug','InnerConfiguration','OuterConfiguration','SignerThumbprint')){
        $invalid=$settings.Clone();$invalid[$field]='';Assert-Refused {Assert-SignPathSettings @invalid} ('Empty '+$field+' accepted.')
    }
    $invalid=$settings.Clone();$invalid.TokenPresent=$false;Assert-Refused {Assert-SignPathSettings @invalid} 'Missing token accepted.'
    $invalid=$settings.Clone();$invalid.OuterConfiguration=$invalid.InnerConfiguration;Assert-Refused {Assert-SignPathSettings @invalid} 'Shared signing configuration accepted.'
    Assert-Refused {Assert-SignerThumbprint ('0'*40)} 'Placeholder signing identity accepted.'

    $publication=New-SignPathDirectory (Join-Path $temporary 'publication')
    [void][IO.Directory]::CreateDirectory((Join-Path $publication 'app'))
    $mit=[IO.File]::ReadAllText((Join-Path $PSScriptRoot '..\LICENSE.proposed'))
    foreach($name in @('LICENSE.proposed','LICENSE','app/LICENSE')){[IO.File]::WriteAllText((Join-Path $publication $name),$mit)}
    Write-SignPathJson (Join-Path $publication 'app/package.json') @{license='MIT'}
    $publicStatus=@{license='MIT';visibility='public';publicationApprovalPending=$false;signPathFoundationApproved=$true;signingServiceConfigured=$true}
    Write-SignPathJson (Join-Path $publication 'SOURCE_PREPARATION.json') $publicStatus
    Assert-SignPathPublication $publication; $checks++
    Write-SignPathJson (Join-Path $publication 'app/package.json') @{}
    Assert-Refused {Assert-SignPathPublication $publication} 'Missing package license accepted.'
    Write-SignPathJson (Join-Path $publication 'app/package.json') @{license='UNLICENSED'}
    Assert-Refused {Assert-SignPathPublication $publication} 'Unlicensed package accepted.'
    Write-SignPathJson (Join-Path $publication 'app/package.json') @{license='MIT'}
    [IO.File]::WriteAllText((Join-Path $publication 'LICENSE'),'All rights reserved.')
    Assert-Refused {Assert-SignPathPublication $publication} 'Proprietary root license accepted.'
    [IO.File]::WriteAllText((Join-Path $publication 'LICENSE'),$mit)
    [IO.File]::WriteAllText((Join-Path $publication 'app/LICENSE'),'All rights reserved.')
    Assert-Refused {Assert-SignPathPublication $publication} 'Proprietary app license accepted.'
    [IO.File]::WriteAllText((Join-Path $publication 'app/LICENSE'),$mit)
    foreach($change in @(@('publicationApprovalPending',$true),@('visibility','private'),@('signPathFoundationApproved',$false),@('signingServiceConfigured',$false))){
        $invalidStatus=$publicStatus.Clone();$invalidStatus[$change[0]]=$change[1]
        Write-SignPathJson (Join-Path $publication 'SOURCE_PREPARATION.json') $invalidStatus
        Assert-Refused {Assert-SignPathPublication $publication} ('Missing approval/configuration accepted: '+$change[0])
    }

    $paths=@(Get-SignPathPayloadPaths)
    Assert-That ($paths -ccontains 'store-runtime.json') 'Native-helper requirement marker missing.'
    Assert-That (@($paths | Where-Object {$_ -match '\.(ps1|pfx|key|sqlite|log)$|(^|/)(data|tests|launcher)/'}).Count -eq 0) 'Runtime payload includes scripts, build sources or private data.'
    Assert-That (@($paths | Where-Object {$_ -like '*.exe'}).Count -eq 2) 'Unexpected executable in payload.'
    $payload=New-SignPathDirectory (Join-Path $temporary 'payload')
    [IO.File]::WriteAllText((Join-Path $payload 'a.txt'),'alpha')
    $inventory=@(Get-PayloadInventory $payload @('a.txt'))
    Assert-PayloadInventory $payload $inventory; $checks++
    Assert-Refused {Get-PayloadInventory $payload @('../a.txt')} 'Traversal accepted.'
    Assert-Refused {Get-PayloadInventory $payload @('a.txt','A.txt')} 'Windows case collision accepted.'
    Assert-Refused {Get-PayloadInventory $payload @('missing.txt')} 'Missing input accepted.'
    [IO.File]::WriteAllText((Join-Path $payload 'extra.txt'),'unexpected')
    Assert-Refused {Assert-PayloadInventory $payload $inventory} 'Extra file accepted.'
    [IO.File]::Delete((Join-Path $payload 'extra.txt'))
    [IO.File]::WriteAllText((Join-Path $payload 'a.txt'),'changed')
    Assert-Refused {Assert-PayloadInventory $payload $inventory} 'Changed bytes accepted.'
    Assert-Refused {New-SignPathDirectory $payload} 'Existing output directory accepted.'

    # Synthetic PE-shaped bytes, not a program or a real signature. These exercise
    # the exact permitted signing mutation boundary independently of Windows trust.
    [byte[]]$before=[byte[]]::new(1025)
    $before[0]=0x4d;$before[1]=0x5a
    [BitConverter]::GetBytes([int]0x80).CopyTo($before,0x3c)
    [BitConverter]::GetBytes([uint32]0x4550).CopyTo($before,0x80)
    [BitConverter]::GetBytes([uint16]0xf0).CopyTo($before,0x80+20)
    [BitConverter]::GetBytes([uint16]0x20b).CopyTo($before,0x98)
    [BitConverter]::GetBytes([uint32]16).CopyTo($before,0x98+108)
    $before[600]=42
    $a=Get-PeSignatureLayout $before
    [byte[]]$after=[byte[]]::new(1048)
    [Array]::Copy($before,$after,$before.Length)
    [BitConverter]::GetBytes([uint32]1032).CopyTo($after,$a.security)
    [BitConverter]::GetBytes([uint32]16).CopyTo($after,$a.security+4)
    [BitConverter]::GetBytes([uint32]1234).CopyTo($after,$a.checksum)
    $unsignedFile=Join-Path $temporary 'unsigned.fixture'
    $signedFile=Join-Path $temporary 'signed.fixture'
    [IO.File]::WriteAllBytes($unsignedFile,$before)
    [IO.File]::WriteAllBytes($signedFile,$after)
    Assert-SignedPeContent $unsignedFile $signedFile;$checks++
    $changed=$after.Clone();$changed[600]=43;[IO.File]::WriteAllBytes($signedFile,$changed)
    Assert-Refused {Assert-SignedPeContent $unsignedFile $signedFile} 'Executable content changed during signing.'
    $changed=$after.Clone();$changed[1026]=1;[IO.File]::WriteAllBytes($signedFile,$changed)
    Assert-Refused {Assert-SignedPeContent $unsignedFile $signedFile} 'Nonzero alignment padding accepted.'
    $changed=$after.Clone();[BitConverter]::GetBytes([uint32]15).CopyTo($changed,$a.security+4);[IO.File]::WriteAllBytes($signedFile,$changed)
    Assert-Refused {Assert-SignedPeContent $unsignedFile $signedFile} 'Post-certificate overlay accepted.'
    $changed=$after.Clone();[BitConverter]::GetBytes([uint32]1024).CopyTo($changed,$a.security);[IO.File]::WriteAllBytes($signedFile,$changed)
    Assert-Refused {Assert-SignedPeContent $unsignedFile $signedFile} 'Overlapping certificate table accepted.'
    $changed=$before.Clone();$changed[0]=0;Assert-Refused {Get-PeSignatureLayout $changed} 'Non-PE content accepted.'
    Assert-Refused {Assert-OwnedSignature $unsignedFile $settings.SignerThumbprint} 'Unsigned fixture passed the trusted-signature check.'

    foreach($name in @('inner-artifact.xml','outer-artifact.xml')){
        $xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load((Join-Path $PSScriptRoot ('..\packaging\signpath\'+$name)))
        $ns=[Xml.XmlNamespaceManager]::new($xml.NameTable);$ns.AddNamespace('s','http://signpath.io/artifact-configuration/v1')
        $pe=$xml.SelectNodes('/s:artifact-configuration/s:zip-file/s:pe-file',$ns)
        Assert-That ($pe.Count -eq 1 -and $pe[0].GetAttribute('path') -ceq 'DomTrack-UA.exe') 'Service configuration signs unexpected files.'
        Assert-That ($pe[0].GetAttribute('product-name') -ceq 'DomTrack UA' -and $pe[0].GetAttribute('file-version') -ceq '${version}') 'Service configuration lacks product/version restrictions.'
        Assert-That ($xml.SelectSingleNode('/s:artifact-configuration/s:parameters/s:parameter[@name="version"]',$ns).GetAttribute('required') -ceq 'true') 'Version is not required.'
    }
    Write-Output ("SignPath boundary tests passed: $checks. No application, signing service, certificate import or real credential operation was executed.")
} finally {
    # Only remove the freshly created test directory under the resolved temp root.
    $safeRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    $resolved=[IO.Path]::GetFullPath($temporary)
    if($resolved.StartsWith($safeRoot,[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolved) -like 'domtrack-signpath-contracts-*') { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
