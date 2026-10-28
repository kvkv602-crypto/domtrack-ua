#Requires -Version 7.0
# Release signing uses an owner-selected certificate already in CurrentUser\My.
# This script never creates certificates or changes trust/security policies.

function Assert-DomTrackReleaseOptions {
    param([string]$BuildMode, [string]$CertificateThumbprint, [string]$SignToolPath, [string]$TimestampUrl)
    if ($BuildMode -notin @('Release','Development')) { throw 'BuildMode: Release или Development.' }
    if ($BuildMode -eq 'Development') {
        if ($CertificateThumbprint -or $SignToolPath) { throw 'Параметры подписи допустимы только в режиме Release.' }
        return
    }
    if ($CertificateThumbprint -notmatch '^[A-Fa-f0-9]{40}$') { throw 'Выпуск заблокирован: укажите отпечаток своего доверенного сертификата подписи кода. Неподписанный EXE не является готовым релизом.' }
    if ([string]::IsNullOrWhiteSpace($SignToolPath)) { throw 'Укажите путь к официальному signtool.exe из Windows SDK.' }
    $taskTimestamp = $null
    if (-not [Uri]::TryCreate($TimestampUrl,[UriKind]::Absolute,[ref]$taskTimestamp) -or $taskTimestamp.Scheme -notin @('http','https') -or $taskTimestamp.UserInfo -or $taskTimestamp.Fragment) { throw 'Нужен URL сервера RFC3161 без логина и пароля.' }
}

function New-DomTrackSigningContext {
    param([string]$BuildMode, [string]$CertificateThumbprint, [string]$SignToolPath, [string]$TimestampUrl)
    Assert-DomTrackReleaseOptions @PSBoundParameters
    if ($BuildMode -eq 'Development') { return [pscustomobject]@{ Enabled=$false; Mode=$BuildMode } }
    $taskTool = Get-Item -LiteralPath ([IO.Path]::GetFullPath($SignToolPath)) -ErrorAction Stop
    $taskToolSignature = Get-AuthenticodeSignature -LiteralPath $taskTool.FullName
    if ($taskTool.PSIsContainer -or $taskTool.Name -ine 'signtool.exe' -or $taskToolSignature.Status -ne 'Valid' -or $taskToolSignature.SignerCertificate.Subject -notmatch '(?:^|,\s*)O=Microsoft Corporation(?:,|$)') { throw 'Требуется подлинный signtool.exe Microsoft с действительной подписью.' }
    $taskThumbprint = $CertificateThumbprint.ToUpperInvariant()
    # Read only the explicitly selected public certificate; no key is exported.
    $taskCertificate = Get-Item -LiteralPath ('Cert:\CurrentUser\My\' + $taskThumbprint) -ErrorAction Stop
    if ($taskCertificate.Thumbprint -ne $taskThumbprint -or -not $taskCertificate.HasPrivateKey) { throw 'Сертификат не найден или у владельца нет доступа к ключу подписи.' }
    if ($taskCertificate.Subject -eq $taskCertificate.Issuer) { throw 'Самоподписанный сертификат не подходит для доверенного публичного выпуска.' }
    $taskNow = [DateTime]::UtcNow
    if ($taskCertificate.NotBefore.ToUniversalTime() -gt $taskNow -or $taskCertificate.NotAfter.ToUniversalTime() -le $taskNow) { throw 'Срок действия сертификата не подходит для подписи.' }
    $taskCodeSigningUsage = @($taskCertificate.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.37' } | ForEach-Object { $_.EnhancedKeyUsages } | Where-Object { $_.Value -eq '1.3.6.1.5.5.7.3.3' })
    if (-not $taskCodeSigningUsage.Count) { throw 'Сертификат не предназначен для подписи кода.' }
    return [pscustomobject]@{ Enabled=$true; Mode=$BuildMode; Tool=$taskTool.FullName; Thumbprint=$taskThumbprint; TimestampUrl=$TimestampUrl }
}

function Invoke-DomTrackFileSignature {
    param([Parameter(Mandatory)]$Context,[Parameter(Mandatory)][string]$LiteralPath)
    if (-not $Context.Enabled) { return }
    $taskFile = Get-Item -LiteralPath ([IO.Path]::GetFullPath($LiteralPath)) -ErrorAction Stop
    if ($taskFile.PSIsContainer -or $taskFile.Extension -notin @('.exe','.ps1')) { throw 'Подписываются только явно выбранные собственные EXE и PS1.' }
    # SHA1 is the certificate identifier required by SignTool, not the file digest.
    $taskArguments = @('sign','/sha1',$Context.Thumbprint,'/s','My','/fd','SHA256','/tr',$Context.TimestampUrl,'/td','SHA256','/d','DomTrack UA',$taskFile.FullName)
    & $Context.Tool @taskArguments
    if ($LASTEXITCODE -ne 0) { throw ('Ошибка подписи: ' + $taskFile.Name) }
    $taskSignature = Get-AuthenticodeSignature -LiteralPath $taskFile.FullName
    if ($taskSignature.Status -ne 'Valid' -or $taskSignature.SignerCertificate.Thumbprint -ne $Context.Thumbprint -or $null -eq $taskSignature.TimeStamperCertificate) { throw ('Подпись, доверие или отметка времени не подтверждены: ' + $taskFile.Name) }
    & $Context.Tool verify /pa /all /tw $taskFile.FullName
    if ($LASTEXITCODE -ne 0) { throw ('Проверка подписи завершилась ошибкой: ' + $taskFile.Name) }
}

function Assert-DomTrackPayloadSignatures {
    param([Parameter(Mandatory)]$Context,[Parameter(Mandatory)][string]$AppRoot)
    if (-not $Context.Enabled) { return }
    # Includes dependencies. Vendor binaries retain their original publisher.
    foreach ($taskFile in (Get-ChildItem -LiteralPath $AppRoot -Recurse -File | Where-Object { $_.Extension -in @('.exe','.dll','.ps1') })) {
        $taskSignature = Get-AuthenticodeSignature -LiteralPath $taskFile.FullName
        if ($taskSignature.Status -ne 'Valid') { throw ('Релиз содержит неподписанный или недоверенный компонент: ' + $taskFile.Name) }
    }
}
