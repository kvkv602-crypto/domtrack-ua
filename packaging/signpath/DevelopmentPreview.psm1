Set-StrictMode -Version Latest

function Get-DevelopmentPreviewFileName {
    param([string]$Version)
    if ($Version -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' -or @($Version.Split('.') | Where-Object { [long]$_ -gt 65535 }).Count -gt 0) { throw 'A three-component numeric app version is required.' }
    return 'DomTrack-UA-'+$Version+'-Development-Unsigned.exe'
}

function Assert-DevelopmentPreviewFileName {
    param([string]$File,[string]$Version)
    if ([IO.Path]::GetFileName($File) -cne (Get-DevelopmentPreviewFileName $Version)) { throw 'Development packages must be explicitly named Development-Unsigned.exe.' }
}

function Assert-DevelopmentPreviewResources {
    param([string]$ResourcesDirectory,[object[]]$Inventory,[string]$PayloadSha256,[string]$ManifestSha256)
    $payload=Join-Path $ResourcesDirectory 'DomTrack.Payload'
    $manifest=Join-Path $ResourcesDirectory 'DomTrack.Manifest'
    foreach($pair in @(@($payload,$PayloadSha256),@($manifest,$ManifestSha256))){
        if($pair[1] -cnotmatch '^[a-f0-9]{64}$' -or (Get-FileHash -LiteralPath $pair[0] -Algorithm SHA256).Hash.ToLowerInvariant() -cne $pair[1]){throw 'Embedded resource hash differs from the sealed development input.'}
    }
    $expected=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $windowsNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($item in $Inventory){
        if($item.path -cnotmatch '^[A-Za-z0-9_.\-/]+$' -or $item.path -match '(^|/)\.{1,2}(/|$)' -or $item.path.StartsWith('/') -or $item.sha256 -cnotmatch '^[a-f0-9]{64}$' -or [long]$item.bytes -lt 0 -or [long]$item.bytes -gt 512MB -or -not $windowsNames.Add($item.path)){throw 'Invalid or duplicate development inventory.'}
        $expected.Add($item.path,$item)
    }
    $expectedManifest=(@($Inventory | Sort-Object path | ForEach-Object {$_.sha256+"`t"+$_.bytes+"`t"+$_.path}) -join "`n")+"`n"
    if([IO.File]::ReadAllText($manifest,[Text.Encoding]::UTF8) -cne $expectedManifest){throw 'Embedded manifest differs from the reviewed inventory.'}
    $archive=[IO.Compression.ZipFile]::OpenRead($payload)
    try{
        if($archive.Entries.Count -ne $expected.Count){throw 'Embedded ZIP entry count differs from the manifest.'}
        $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($entry in $archive.Entries){
            if(-not $expected.ContainsKey($entry.FullName) -or -not $seen.Add($entry.FullName)){throw 'Unexpected or duplicate embedded ZIP path.'}
            $item=$expected[$entry.FullName]
            if($entry.Length -ne [long]$item.bytes){throw 'Embedded ZIP entry size differs from the manifest.'}
            $stream=$entry.Open();$algorithm=[Security.Cryptography.SHA256]::Create()
            try{$digest=[BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}
            finally{$algorithm.Dispose();$stream.Dispose()}
            if($digest -cne $item.sha256){throw 'Embedded ZIP entry bytes differ from the manifest.'}
        }
    } finally{$archive.Dispose()}
}

Export-ModuleMember -Function Get-DevelopmentPreviewFileName,Assert-DevelopmentPreviewFileName,Assert-DevelopmentPreviewResources
