Set-StrictMode -Version Latest

function Assert-DomTrackStoreIdentity {
    [CmdletBinding()]
    param([AllowNull()][AllowEmptyString()][string]$IdentityName, [AllowNull()][AllowEmptyString()][string]$Publisher, [AllowNull()][AllowEmptyString()][string]$PublisherDisplayName)
    # Validation only: preserve the exact strings supplied by Partner Center. No files/config are written.
    if ([string]::IsNullOrWhiteSpace($IdentityName) -or $IdentityName -cnotmatch '^[A-Za-z0-9.-]{3,50}$') { throw 'IdentityName is required: copy Package/Identity/Name exactly from Partner Center Product identity.' }
    if ([string]::IsNullOrWhiteSpace($Publisher) -or $Publisher.Length -gt 8192 -or $Publisher -cne $Publisher.Trim() -or $Publisher -match '[\x00-\x1f\x7f]' -or $Publisher -notmatch '^CN=.+') { throw 'Publisher is required: copy Package/Identity/Publisher (the complete CN=... distinguished name) exactly from Partner Center.' }
    try { $taskDn = [Security.Cryptography.X509Certificates.X500DistinguishedName]::new($Publisher); if ($taskDn.RawData.Length -eq 0) { throw 'Empty distinguished name.' } } catch { throw 'Publisher is not a valid X.500 distinguished name.' }
    if ([string]::IsNullOrWhiteSpace($PublisherDisplayName) -or $PublisherDisplayName.Length -gt 256 -or $PublisherDisplayName -cne $PublisherDisplayName.Trim() -or $PublisherDisplayName -match '[\x00-\x1f\x7f]') { throw 'PublisherDisplayName is required: copy Package/Properties/PublisherDisplayName exactly from Partner Center.' }
    foreach ($taskValue in @($IdentityName, $Publisher, $PublisherDisplayName)) { if ($taskValue -match '__PARTNER_CENTER_|\$\{|[<>]') { throw 'Unresolved identity placeholders are not allowed.' } }
}

function Assert-DomTrackStoreVersion {
    param([string]$Version)
    if ($Version -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.0$') { throw 'Store package version must contain four integers; the fourth component must be 0.' }
    $taskParts = @($Version.Split('.') | ForEach-Object { [long]$_ })
    if ($taskParts[0] -lt 1 -or @($taskParts | Where-Object { $_ -gt 65535 }).Count -gt 0) { throw 'Store version components must be 0..65535 and the major component must be positive.' }
}

function New-DomTrackStoreManifest {
    param([string]$IdentityName, [string]$Publisher, [string]$PublisherDisplayName, [string]$Version)
    Assert-DomTrackStoreIdentity $IdentityName $Publisher $PublisherDisplayName
    Assert-DomTrackStoreVersion $Version
    $taskXml = [Xml.XmlDocument]::new()
    $taskXml.XmlResolver = $null
    $taskXml.Load((Join-Path $PSScriptRoot 'AppxManifest.xml.template'))
    $taskNs = [Xml.XmlNamespaceManager]::new($taskXml.NameTable)
    $taskNs.AddNamespace('f', 'http://schemas.microsoft.com/appx/manifest/foundation/windows10')
    $taskIdentity = $taskXml.SelectSingleNode('/f:Package/f:Identity', $taskNs)
    $taskIdentity.SetAttribute('Name', $IdentityName)
    $taskIdentity.SetAttribute('Publisher', $Publisher)
    $taskIdentity.SetAttribute('Version', $Version)
    $taskXml.SelectSingleNode('/f:Package/f:Properties/f:PublisherDisplayName', $taskNs).InnerText = $PublisherDisplayName
    return ,$taskXml
}

function Get-DomTrackMsixPayload {
    $taskEntries = @([IO.File]::ReadAllLines((Join-Path $PSScriptRoot 'payload-files.txt')) | Where-Object { $_ -and -not $_.StartsWith('#') })
    if ($taskEntries.Count -lt 10 -or @($taskEntries | Sort-Object -Unique).Count -ne $taskEntries.Count) { throw 'Payload allowlist is empty or contains duplicates.' }
    foreach ($taskEntry in $taskEntries) {
        if ($taskEntry -cnotmatch '^[A-Za-z0-9_.\-/]+$' -or [IO.Path]::IsPathRooted($taskEntry) -or $taskEntry -match '(^|/)\.{1,2}(/|$)|(^|/)(?:data|tests?|launcher|ci|packaging)(/|$)|\.(?:ps1|pfx|p12|pem|key|sqlite.*|log|zip)$|(^|/)\.env') { throw ('Unsafe payload allowlist entry: ' + $taskEntry) }
        if ($taskEntry -match '\.exe$' -and $taskEntry -cnotin @('DomTrack-UA.exe', 'runtime/node.exe')) { throw 'Only the inner launcher and official Node executable belong in the package.' }
    }
    return $taskEntries
}

function New-DomTrackStoreRuntimeMarker {
    # A payload marker, not executable configuration. The selector must fail closed if no native bridge is configured.
    return ([ordered]@{ format = 'domtrack-store-runtime'; version = 1 } | ConvertTo-Json -Compress) + "`n"
}

function Assert-DomTrackPlainFile {
    param([string]$Root, [string]$RelativePath)
    $taskRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $taskPath = [IO.Path]::GetFullPath((Join-Path $taskRoot $RelativePath))
    if (-not $taskPath.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or -not [IO.File]::Exists($taskPath)) { throw ('Missing/outside payload file: ' + $RelativePath) }
    $taskFile = Get-Item -LiteralPath $taskPath -Force
    if (($taskFile.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('Payload reparse points are not allowed: ' + $RelativePath) }
    $taskCurrent = $taskFile.Directory
    while ($null -ne $taskCurrent -and $taskCurrent.FullName.Length -ge $taskRoot.Length) {
        if (($taskCurrent.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('Payload reparse points are not allowed: ' + $RelativePath) }
        if ($taskCurrent.FullName -eq $taskRoot) { break }
        $taskCurrent = $taskCurrent.Parent
    }
}

function Resolve-DomTrackMakeAppx {
    param([string]$MakeAppxPath)
    if ([string]::IsNullOrWhiteSpace($MakeAppxPath)) {
        $taskSdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
        if ([IO.Directory]::Exists($taskSdkRoot)) {
            $taskCandidate = Get-ChildItem -LiteralPath $taskSdkRoot -Directory | Where-Object { $_.Name -match '^10\.0\.[0-9]+\.[0-9]+$' } | Sort-Object { [version]$_.Name } -Descending | ForEach-Object { Join-Path $_.FullName 'x64\makeappx.exe' } | Where-Object { [IO.File]::Exists($_) } | Select-Object -First 1
            if ($taskCandidate) { $MakeAppxPath = $taskCandidate }
        }
    }
    if ([string]::IsNullOrWhiteSpace($MakeAppxPath) -or -not [IO.File]::Exists($MakeAppxPath) -or [IO.Path]::GetFileName($MakeAppxPath) -ine 'makeappx.exe') { throw 'Official Windows SDK MakeAppx.exe was not found. Provide -MakeAppxPath to a verified official SDK tool; no installer is run automatically.' }
    $taskMakeAppx = Get-Item -LiteralPath ([IO.Path]::GetFullPath($MakeAppxPath))
    $taskSignature = Get-AuthenticodeSignature -LiteralPath $taskMakeAppx.FullName
    if ($taskSignature.Status -ne 'Valid' -or $null -eq $taskSignature.SignerCertificate -or $taskSignature.SignerCertificate.Subject -notmatch '(^|,\s*)O=Microsoft Corporation(,|$)' -or $taskMakeAppx.VersionInfo.OriginalFilename -ine 'MakeAppx.exe') { throw 'MakeAppx must have a valid trusted Microsoft Authenticode signature and its original SDK file identity.' }
    return [pscustomobject]@{ Path = $taskMakeAppx.FullName; Sha256 = (Get-FileHash -LiteralPath $taskMakeAppx.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); Version = $taskMakeAppx.VersionInfo.FileVersion; Publisher = $taskSignature.SignerCertificate.Subject }
}

function New-DomTrackMsixAssets {
    param([string]$IconPath, [string]$OutputDirectory)
    Add-Type -AssemblyName System.Drawing
    $taskIcon = [Drawing.Icon]::new($IconPath, 256, 256)
    try {
        $taskSource = $taskIcon.ToBitmap()
        try {
            [IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
            foreach ($taskAsset in @(@('StoreLogo.png', 50), @('Square44x44Logo.png', 44), @('Square150x150Logo.png', 150))) {
                $taskSize = [int]$taskAsset[1]
                $taskBitmap = [Drawing.Bitmap]::new($taskSize, $taskSize, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
                try {
                    $taskBitmap.SetResolution(96, 96)
                    $taskGraphics = [Drawing.Graphics]::FromImage($taskBitmap)
                    try {
                        $taskGraphics.Clear([Drawing.Color]::Transparent)
                        $taskGraphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                        $taskGraphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                        $taskInset = [int][Math]::Floor($taskSize * 0.1)
                        $taskGraphics.DrawImage($taskSource, $taskInset, $taskInset, $taskSize - 2 * $taskInset, $taskSize - 2 * $taskInset)
                    } finally { $taskGraphics.Dispose() }
                    $taskBitmap.Save((Join-Path $OutputDirectory $taskAsset[0]), [Drawing.Imaging.ImageFormat]::Png)
                } finally { $taskBitmap.Dispose() }
            }
        } finally { $taskSource.Dispose() }
    } finally { $taskIcon.Dispose() }
}

Export-ModuleMember -Function Assert-DomTrackStoreIdentity, Assert-DomTrackStoreVersion, New-DomTrackStoreManifest, Get-DomTrackMsixPayload, New-DomTrackStoreRuntimeMarker, Assert-DomTrackPlainFile, Resolve-DomTrackMakeAppx, New-DomTrackMsixAssets
