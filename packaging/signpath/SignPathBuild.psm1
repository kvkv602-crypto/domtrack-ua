Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot '..\msix\StorePackage.psm1') -Scope Local

function Assert-SignPathSettings {
    param([string]$Enabled, [string]$OrganizationId, [string]$ProjectSlug, [string]$PolicySlug, [string]$InnerConfiguration, [string]$OuterConfiguration, [string]$SignerThumbprint, [bool]$TokenPresent)
    if ($Enabled -cne 'true' -or -not $TokenPresent) { throw 'Approved SignPath signing is not enabled or its protected environment token is absent.' }
    $id = [Guid]::Empty
    if (-not [Guid]::TryParse($OrganizationId, [ref]$id) -or $id -eq [Guid]::Empty) { throw 'An actual SignPath organization ID is required.' }
    foreach ($slug in @($ProjectSlug, $PolicySlug, $InnerConfiguration, $OuterConfiguration)) {
        if ($slug -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$' -or $slug -match 'placeholder|replace-me|example') { throw 'Actual approved SignPath project, policy and artifact configuration slugs are required.' }
    }
    if ($InnerConfiguration -ceq $OuterConfiguration) { throw 'Inner and outer signing must have separate reviewed artifact configurations.' }
    Assert-SignerThumbprint $SignerThumbprint
}

function Assert-SignerThumbprint {
    param([string]$SignerThumbprint)
    if ($SignerThumbprint -cnotmatch '^[A-Fa-f0-9]{40}$' -or $SignerThumbprint -match '^(.)\1+$') { throw 'Pin the actual approved signing certificate thumbprint; no certificate is created or imported.' }
}

function Assert-SignPathPublication {
    param([string]$RepositoryRoot)
    $root=[IO.Path]::GetFullPath($RepositoryRoot)
    $package=Get-Content -LiteralPath (Join-Path $root 'app/package.json') -Raw | ConvertFrom-Json
    $status=Get-Content -LiteralPath (Join-Path $root 'SOURCE_PREPARATION.json') -Raw | ConvertFrom-Json
    $proposed=[IO.File]::ReadAllText((Join-Path $root 'LICENSE.proposed'))
    if ($package.license -cne 'MIT' -or $status.license -cne 'MIT' -or $status.visibility -cne 'public' -or $status.publicationApprovalPending -isnot [bool] -or $status.publicationApprovalPending -ne $false -or $status.signPathFoundationApproved -ne $true -or $status.signingServiceConfigured -ne $true) { throw 'Publication, MIT licensing and actual Foundation/service approval must be completed before signing.' }
    if ($proposed -cnotmatch '^MIT License\r?\n' -or [IO.File]::ReadAllText((Join-Path $root 'LICENSE')) -cne $proposed -or [IO.File]::ReadAllText((Join-Path $root 'app/LICENSE')) -cne $proposed) { throw 'Root and app licenses must exactly match the owner-reviewed MIT proposal.' }
}

function New-SignPathDirectory {
    param([string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    if ([IO.File]::Exists($full) -or [IO.Directory]::Exists($full)) { throw 'Output directory must be new; existing files are never reused or deleted.' }
    [void][IO.Directory]::CreateDirectory($full)
    return $full
}

function Get-SignPathPayloadPaths {
    # This marker is also used for the native-only non-Store payload. It selects
    # no PowerShell fallback; it does not claim that a Store package is installed.
    return @((Get-DomTrackMsixPayload)) + @('store-runtime.json')
}

function Get-PayloadInventory {
    param([string]$Directory, [string[]]$ExpectedPaths)
    $root = [IO.Path]::GetFullPath($Directory).TrimEnd('\','/')
    if (-not [IO.Directory]::Exists($root)) { throw 'Payload directory is missing.' }
    $expected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $windowsNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($relative in $ExpectedPaths) {
        if ($relative -cnotmatch '^[A-Za-z0-9_.\-/]+$' -or $relative -match '(^|/)\.{1,2}(/|$)' -or -not $expected.Add($relative) -or -not $windowsNames.Add($relative)) { throw 'Invalid or duplicate expected file name.' }
        Assert-DomTrackPlainFile $root $relative
    }
    $files = @(Get-ChildItem -LiteralPath $root -Recurse -File -Force)
    if ($files.Count -ne $expected.Count) { throw 'Payload file count differs from the reviewed allowlist.' }
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($root.Length+1).Replace('\','/')
        if (-not $expected.Contains($relative)) { throw 'Unexpected payload file.' }
    }
    return @($ExpectedPaths | Sort-Object | ForEach-Object {
        $file = Join-Path $root $_
        [pscustomobject]@{ path=$_; bytes=(Get-Item -LiteralPath $file).Length; sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
}

function Assert-PayloadInventory {
    param([string]$Directory, [object[]]$Inventory)
    $actual = @(Get-PayloadInventory $Directory @($Inventory | ForEach-Object { $_.path }))
    $expected = @{}
    foreach ($item in $Inventory) {
        if ($item.sha256 -cnotmatch '^[a-f0-9]{64}$' -or [long]$item.bytes -lt 0 -or $expected.ContainsKey($item.path)) { throw 'Invalid saved inventory.' }
        $expected.Add($item.path, $item)
    }
    foreach ($item in $actual) {
        $original = $expected[$item.path]
        if ($item.sha256 -cne $original.sha256 -or [long]$item.bytes -ne [long]$original.bytes) { throw ('Payload bytes changed: ' + $item.path) }
    }
}

function Assert-VendorPayload {
    param([string]$Directory)
    $lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\..\ci\dependency-lock.json') -Raw | ConvertFrom-Json
    $node = Join-Path $Directory 'runtime\node.exe'
    if ((Get-FileHash -LiteralPath $node -Algorithm SHA256).Hash.ToLowerInvariant() -cne $lock.node.exeSha256) { throw 'Node executable differs from the pinned official dependency.' }
    foreach ($relative in @('runtime/node.exe','runtime/webview2/Microsoft.Web.WebView2.Core.dll','runtime/webview2/Microsoft.Web.WebView2.WinForms.dll','runtime/webview2/WebView2Loader.dll')) {
        $signature = Get-AuthenticodeSignature -LiteralPath (Join-Path $Directory $relative)
        $publisher = if ($relative -ceq 'runtime/node.exe') { 'OpenJS Foundation' } else { 'Microsoft Corporation' }
        if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or $signature.SignerCertificate.Subject -notmatch ('(^|,\s*)O=' + [Regex]::Escape($publisher) + '(,|$)')) { throw ('Vendor signature did not validate: ' + $relative) }
    }
}

function Assert-OwnedSignature {
    param([string]$File, [string]$SignerThumbprint)
    Assert-SignerThumbprint $SignerThumbprint
    $signature = Get-AuthenticodeSignature -LiteralPath $File
    if ($signature.Status -ne 'Valid' -or $signature.SignatureType -ne 'Authenticode' -or $null -eq $signature.SignerCertificate -or $null -eq $signature.TimeStamperCertificate) { throw 'A valid trusted timestamped Authenticode signature is required.' }
    $certificate = $signature.SignerCertificate
    if ($certificate.Thumbprint -ine $SignerThumbprint -or $certificate.Subject -eq $certificate.Issuer) { throw 'Signing certificate does not match the approved non-self-signed identity.' }
    $usage = @($certificate.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.37' } | ForEach-Object { $_.EnhancedKeyUsages } | Where-Object { $_.Value -eq '1.3.6.1.5.5.7.3.3' })
    if ($usage.Count -eq 0) { throw 'Signer certificate is not authorized for code signing.' }
    return [pscustomobject]@{ thumbprint=$certificate.Thumbprint; publisher=$certificate.Subject; status='Valid'; timestamped=$true }
}

function Get-PeSignatureLayout {
    param([byte[]]$Bytes)
    if ($Bytes.Length -lt 256 -or $Bytes[0] -ne 0x4d -or $Bytes[1] -ne 0x5a) { throw 'Invalid PE header.' }
    $pe = [BitConverter]::ToInt32($Bytes, 0x3c)
    if ($pe -lt 0x40 -or $pe -gt $Bytes.Length-24 -or [BitConverter]::ToUInt32($Bytes,$pe) -ne 0x4550) { throw 'Invalid PE offset.' }
    $optional = $pe+24
    $optionalSize = [BitConverter]::ToUInt16($Bytes,$pe+20)
    if ($optionalSize -lt 136 -or $optional+$optionalSize -gt $Bytes.Length) { throw 'Invalid PE optional header.' }
    $magic = [BitConverter]::ToUInt16($Bytes,$optional)
    if ($magic -eq 0x10b) { $directory = $optional+96; $number = $optional+92 }
    elseif ($magic -eq 0x20b) { $directory = $optional+112; $number = $optional+108 }
    else { throw 'Unsupported PE optional header.' }
    $security = $directory+32
    if ($security+8 -gt $optional+$optionalSize -or [BitConverter]::ToUInt32($Bytes,$number) -lt 5) { throw 'Missing PE security directory.' }
    return [pscustomobject]@{ checksum=$optional+64; security=$security; certificateOffset=[long][BitConverter]::ToUInt32($Bytes,$security); certificateSize=[long][BitConverter]::ToUInt32($Bytes,$security+4) }
}

function Assert-SignedPeContent {
    param([string]$UnsignedFile, [string]$SignedFile)
    foreach ($file in @($UnsignedFile,$SignedFile)) { if ((Get-Item -LiteralPath $file).Length -gt 512MB) { throw 'PE file is too large.' } }
    [byte[]]$before = [IO.File]::ReadAllBytes($UnsignedFile)
    [byte[]]$after = [IO.File]::ReadAllBytes($SignedFile)
    $a = Get-PeSignatureLayout $before; $b = Get-PeSignatureLayout $after
    if ($a.checksum -ne $b.checksum -or $a.security -ne $b.security -or $a.certificateOffset -ne 0 -or $a.certificateSize -ne 0) { throw 'Expected an unsigned baseline with the same PE layout.' }
    if ($b.certificateOffset -lt $before.Length -or $b.certificateOffset -gt $before.Length+7 -or $b.certificateOffset%8 -ne 0 -or $b.certificateSize -lt 8 -or $b.certificateOffset+$b.certificateSize -ne $after.Length) { throw 'Signing changed PE structure beyond the certificate table.' }
    for ($i=$before.Length; $i -lt $b.certificateOffset; $i++) { if ($after[$i] -ne 0) { throw 'Unexpected data before certificate table.' } }
    [Array]::Clear($before,$a.checksum,4); [Array]::Clear($before,$a.security,8)
    [byte[]]$content = [byte[]]::new($before.Length)
    [Array]::Copy($after,$content,$before.Length)
    [Array]::Clear($content,$b.checksum,4); [Array]::Clear($content,$b.security,8)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $beforeHash = [Convert]::ToBase64String($algorithm.ComputeHash($before))
        $afterHash = [Convert]::ToBase64String($algorithm.ComputeHash($content))
    } finally { $algorithm.Dispose() }
    if ($beforeHash -cne $afterHash) { throw 'Signed executable content differs from the reviewed unsigned build.' }
}

function Write-SignPathJson {
    param([string]$File, [object]$Value)
    [IO.File]::WriteAllText($File,($Value | ConvertTo-Json -Depth 15)+"`n",[Text.UTF8Encoding]::new($false))
}

Export-ModuleMember -Function Assert-SignPathSettings,Assert-SignerThumbprint,Assert-SignPathPublication,New-SignPathDirectory,Get-SignPathPayloadPaths,Get-PayloadInventory,Assert-PayloadInventory,Assert-VendorPayload,Assert-OwnedSignature,Get-PeSignatureLayout,Assert-SignedPeContent,Write-SignPathJson
