#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Signing.ps1')
$taskPassed = 0
function Test-Contract([string]$Name,[scriptblock]$Body) { & $Body; $script:taskPassed++; Write-Output ('PASS '+$Name) }
function Must-Reject([scriptblock]$Body,[string]$Message) { $taskRejected=$false; try { & $Body } catch { $taskRejected=$true; if($_.Exception.Message -notmatch $Message){throw} }; if(-not $taskRejected){throw 'Invalid release configuration was accepted.'} }
Test-Contract 'release without a certificate fails before tool or certificate lookup' {
    Must-Reject { New-DomTrackSigningContext -BuildMode Release -CertificateThumbprint '' -SignToolPath 'not-installed.exe' -TimestampUrl 'http://timestamp.digicert.com' } 'Выпуск заблокирован'
}
Test-Contract 'invalid certificate identifiers cannot become store paths' {
    foreach($taskBad in @('../My',('A'*39),('G'*40),('A'*41))){Must-Reject { Assert-DomTrackReleaseOptions -BuildMode Release -CertificateThumbprint $taskBad -SignToolPath 'unused.exe' -TimestampUrl 'http://timestamp.digicert.com' } 'Выпуск заблокирован'}
}
Test-Contract 'release requires an explicit official signing tool' {
    Must-Reject { Assert-DomTrackReleaseOptions -BuildMode Release -CertificateThumbprint ('A'*40) -SignToolPath '' -TimestampUrl 'http://timestamp.digicert.com' } 'signtool'
}
Test-Contract 'timestamp URL cannot carry credentials or another protocol' {
    foreach($taskBad in @('file:///C:/example','http://user:password@example.invalid','not a URL','https://example.invalid/#fragment')){Must-Reject { Assert-DomTrackReleaseOptions -BuildMode Release -CertificateThumbprint ('A'*40) -SignToolPath 'unused.exe' -TimestampUrl $taskBad } 'RFC3161'}
}
Test-Contract 'development configuration never accesses a private key or signing tool' {
    $taskContext=New-DomTrackSigningContext -BuildMode Development -TimestampUrl ''
    if($taskContext.Enabled){throw 'Development unexpectedly enabled signing.'}
    Must-Reject { New-DomTrackSigningContext -BuildMode Development -CertificateThumbprint ('A'*40) -TimestampUrl '' } 'только в режиме Release'
}
Test-Contract 'valid options can be checked without opening a certificate or running software' {
    Assert-DomTrackReleaseOptions -BuildMode Release -CertificateThumbprint ('A'*40) -SignToolPath 'unused.exe' -TimestampUrl 'http://timestamp.digicert.com'
}
Test-Contract 'build signs payload before hashing and outer EXE before final hashes' {
    $taskBuild=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Build-OneFile.ps1'))
    $taskPreflight=$taskBuild.IndexOf('$taskSigning = New-DomTrackSigningContext')
    $taskFirstWrite=$taskBuild.IndexOf('[IO.Directory]::CreateDirectory')
    $taskPayloadCheck=$taskBuild.IndexOf('Assert-DomTrackPayloadSignatures -Context')
    $taskManifest=$taskBuild.IndexOf('$taskLines = foreach')
    $taskOuterSign=$taskBuild.IndexOf('Invoke-DomTrackFileSignature -Context $taskSigning -LiteralPath $OutputFile')
    $taskFinalHash=$taskBuild.LastIndexOf('FileSHA256=(Get-FileHash')
    if($taskPreflight -lt 0 -or $taskFirstWrite -le $taskPreflight -or $taskPayloadCheck -lt 0 -or $taskManifest -le $taskPayloadCheck -or $taskOuterSign -le $taskManifest -or $taskFinalHash -le $taskOuterSign){throw 'Unsafe signing/hash order.'}
    if($taskBuild -notmatch "BuildMode = 'Release'" -or $taskBuild -notmatch 'Development-Unsigned.exe'){throw 'Unsigned release is not clearly separated.'}
}
Write-Output ('RESULT '+$taskPassed+' signing-contract tests passed; no certificates accessed, binaries compiled, signatures created or security settings changed.')
