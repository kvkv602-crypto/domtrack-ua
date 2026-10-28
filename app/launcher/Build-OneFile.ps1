param(
    [string]$OutputFile = '',
    [string]$BuildDirectory = '',
    [ValidateSet('Release','Development')][string]$BuildMode = 'Release',
    [string]$CertificateThumbprint = '',
    [string]$SignToolPath = '',
    [string]$TimestampUrl = 'http://timestamp.digicert.com'
)
#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Signing.ps1')
# Refuse an unsigned public release before modifying any existing build files.
$taskSigning = New-DomTrackSigningContext -BuildMode $BuildMode -CertificateThumbprint $CertificateThumbprint -SignToolPath $SignToolPath -TimestampUrl $TimestampUrl
$taskAppRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskBuildRoot = if ($BuildDirectory) { [IO.Path]::GetFullPath($BuildDirectory) } else { [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\work\singlefile-build')) }
if (-not $OutputFile) { $OutputFile = Join-Path $taskAppRoot $(if ($BuildMode -eq 'Release') { '..\DomTrack-UA-OneFile.exe' } else { '..\DomTrack-UA-Development-Unsigned.exe' }) }
if ($BuildMode -eq 'Development' -and [IO.Path]::GetFileName($OutputFile) -notlike '*Development-Unsigned.exe') { throw 'Для Development имя EXE должно завершаться на Development-Unsigned.exe.' }
$OutputFile = [IO.Path]::GetFullPath($OutputFile)
[IO.Directory]::CreateDirectory($taskBuildRoot) | Out-Null
$taskFramework = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
$taskCompiler = Join-Path $taskFramework 'csc.exe'
if (-not [IO.File]::Exists($taskCompiler)) { throw 'Требуется компилятор .NET Framework x64.' }
if ([IO.Directory]::Exists((Join-Path $taskAppRoot 'data'))) { throw 'Рабочая папка data не должна входить в сборку.' }
$taskUnexpected = Get-ChildItem -LiteralPath $taskAppRoot -File -Recurse | Where-Object { $_.Name -match '(\.sqlite(?:-wal|-shm)?$|\.enc\.json$|^\.env$|\.log$)' }
if ($taskUnexpected) { throw 'В исходной папке присутствуют рабочие данные.' }
$taskWebView = Join-Path $taskAppRoot 'runtime\webview2'
$taskWebViewReferences = @(('/reference:' + (Join-Path $taskWebView 'Microsoft.Web.WebView2.Core.dll')), ('/reference:' + (Join-Path $taskWebView 'Microsoft.Web.WebView2.WinForms.dll')))
foreach($taskDependency in @('Microsoft.Web.WebView2.Core.dll','Microsoft.Web.WebView2.WinForms.dll','WebView2Loader.dll','LICENSE.txt','NOTICE.txt')) { if(-not(Test-Path -LiteralPath (Join-Path $taskWebView $taskDependency))) { throw ('Отсутствует компонент встроенного окна: ' + $taskDependency) } }
& $taskCompiler /nologo /target:winexe /platform:x64 /optimize+ ('/out:' + (Join-Path $taskAppRoot 'DomTrack-UA.exe')) ('/win32icon:' + (Join-Path $taskAppRoot 'public\app.ico')) /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Security.dll /reference:System.Web.Extensions.dll @taskWebViewReferences (Join-Path $PSScriptRoot 'DomTrack.cs') (Join-Path $PSScriptRoot 'AppWindow.cs') (Join-Path $PSScriptRoot 'WindowsBridge.cs')
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать вложенный launcher.' }
Invoke-DomTrackFileSignature -Context $taskSigning -LiteralPath (Join-Path $taskAppRoot 'DomTrack-UA.exe')
foreach ($taskOwnedScript in @('lib\key-vault.ps1','lib\notify.ps1')) { Invoke-DomTrackFileSignature -Context $taskSigning -LiteralPath (Join-Path $taskAppRoot $taskOwnedScript) }
foreach ($taskOwnedScript in (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File)) { Invoke-DomTrackFileSignature -Context $taskSigning -LiteralPath $taskOwnedScript.FullName }
Assert-DomTrackPayloadSignatures -Context $taskSigning -AppRoot $taskAppRoot
$taskPortTests = Join-Path $taskBuildRoot 'PortDiscovery.Tests.exe'
& $taskCompiler /nologo /target:exe /platform:x64 /optimize+ ('/out:' + $taskPortTests) (Join-Path $PSScriptRoot 'PortDiscovery.Tests.cs')
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать проверку автоматического порта.' }
Invoke-DomTrackFileSignature -Context $taskSigning -LiteralPath $taskPortTests
$taskPortSmoke = Join-Path $taskBuildRoot ('ports-' + [Guid]::NewGuid().ToString('N'))
& $taskPortTests (Join-Path $taskAppRoot 'DomTrack-UA.exe') $taskPortSmoke
if ($LASTEXITCODE -ne 0) { throw 'Проверка запуска на свободном порту завершилась ошибкой.' }
$taskDesktopTests = Join-Path $taskBuildRoot 'NativeDesktop.Tests.exe'
& $taskCompiler /nologo /target:exe /platform:x64 /optimize+ ('/out:' + $taskDesktopTests) (Join-Path $PSScriptRoot 'NativeDesktop.Tests.cs')
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать проверки отдельного окна.' }
Invoke-DomTrackFileSignature -Context $taskSigning -LiteralPath $taskDesktopTests
$taskDesktopSmoke = Join-Path $taskBuildRoot ('desktop-' + [Guid]::NewGuid().ToString('N'))
& $taskDesktopTests $taskWebView (Join-Path $taskAppRoot 'DomTrack-UA.exe') $taskDesktopSmoke
if ($LASTEXITCODE -ne 0) { throw 'Проверка встроенного загрузчика или обновления завершилась ошибкой.' }
$taskManifest = Join-Path $taskBuildRoot 'files.manifest'
$taskLines = foreach ($taskFile in (Get-ChildItem -LiteralPath $taskAppRoot -Recurse -File | Sort-Object FullName)) {
    $taskRelative = $taskFile.FullName.Substring($taskAppRoot.Length + 1).Replace('\','/')
    if ($taskRelative -match '[\x00-\x1f]') { throw 'Недопустимое имя файла.' }
    $taskHash = (Get-FileHash -LiteralPath $taskFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskHash + "`t" + $taskFile.Length + "`t" + $taskRelative
}
[IO.File]::WriteAllText($taskManifest, (($taskLines -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskPayload = Join-Path $taskBuildRoot 'payload.zip'
if ([IO.File]::Exists($taskPayload)) { [IO.File]::Delete($taskPayload) }
[IO.Compression.ZipFile]::CreateFromDirectory($taskAppRoot, $taskPayload, [IO.Compression.CompressionLevel]::Optimal, $false)
$taskPayloadHash = (Get-FileHash -LiteralPath $taskPayload -Algorithm SHA256).Hash.ToLowerInvariant()
$taskManifestHash = (Get-FileHash -LiteralPath $taskManifest -Algorithm SHA256).Hash.ToLowerInvariant()
$taskBuildInfo = Join-Path $taskBuildRoot 'BuildInfo.cs'
$taskConstants = 'namespace DomTrackSingleFile { internal static class BuildInfo { internal const string PayloadHash = "' + $taskPayloadHash + '"; internal const string ManifestHash = "' + $taskManifestHash + '"; } }'
[IO.File]::WriteAllText($taskBuildInfo, $taskConstants, (New-Object Text.UTF8Encoding($false)))
$taskSource = Join-Path $PSScriptRoot 'SingleFile.cs'
$taskReferences = @('/reference:System.Windows.Forms.dll','/reference:System.Drawing.dll','/reference:System.IO.Compression.dll')
& $taskCompiler /nologo /target:winexe /platform:x64 /optimize+ ('/out:' + $OutputFile) ('/win32icon:' + (Join-Path $taskAppRoot 'public\app.ico')) ('/win32manifest:' + (Join-Path $PSScriptRoot 'SingleFile.manifest')) @taskReferences ('/resource:' + $taskPayload + ',DomTrack.Payload') ('/resource:' + $taskManifest + ',DomTrack.Manifest') $taskSource $taskBuildInfo
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать однофайловую программу.' }
# The outer signature is added after embedding already-signed components.
Invoke-DomTrackFileSignature -Context $taskSigning -LiteralPath $OutputFile
$taskTests = Join-Path $taskBuildRoot 'SingleFile.Tests.exe'
& $taskCompiler /nologo /target:exe /platform:x64 /optimize+ /main:DomTrackSingleFile.PackageTests ('/out:' + $taskTests) @taskReferences $taskSource $taskBuildInfo (Join-Path $PSScriptRoot 'SingleFile.Tests.cs')
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать проверку упаковки.' }
Invoke-DomTrackFileSignature -Context $taskSigning -LiteralPath $taskTests
$taskSmoke = Join-Path $taskBuildRoot ('smoke-' + [Guid]::NewGuid().ToString('N'))
$taskUnicode = Join-Path $taskSmoke 'Проверка пути с пробелами'
[IO.Directory]::CreateDirectory($taskUnicode) | Out-Null
$taskCopy = Join-Path $taskUnicode 'DomTrack-UA.exe'
[IO.File]::Copy($OutputFile, $taskCopy)
$taskResources = Join-Path $taskSmoke 'resources'
& (Join-Path $PSScriptRoot 'Read-PackageResources.ps1') -Executable $taskCopy -Destination $taskResources
& $taskTests $taskResources (Join-Path $taskSmoke 'extraction')
if ($LASTEXITCODE -ne 0) { throw 'Проверка однофайловой сборки завершилась ошибкой.' }
[pscustomobject]@{Mode=$BuildMode;Signed=[bool]$taskSigning.Enabled;File=$OutputFile;Bytes=(Get-Item -LiteralPath $OutputFile).Length;PayloadSHA256=$taskPayloadHash;FileSHA256=(Get-FileHash -LiteralPath $OutputFile -Algorithm SHA256).Hash;SmokeDirectory=$taskSmoke} | ConvertTo-Json -Compress
