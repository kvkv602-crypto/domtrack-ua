#Requires -Version 7.0
param([string]$AppRoot = (Join-Path $PSScriptRoot '..\app'), [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\dist\unsigned'))
$ErrorActionPreference = 'Stop'
$taskApp = [IO.Path]::GetFullPath($AppRoot)
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if ([IO.Directory]::Exists($taskOutput)) { throw 'OutputDirectory must be new; old artifacts are never deleted or reused.' }
if ([IO.Directory]::Exists((Join-Path $taskApp 'data'))) { throw 'Runtime data must not exist in the source app directory.' }
$taskUnexpected = Get-ChildItem -LiteralPath $taskApp -Recurse -File | Where-Object { $_.Name -match '(\.sqlite(?:-wal|-shm)?$|\.enc\.json$|\.pfx$|\.p12$|\.key$|^\.env(?:\..*)?$|\.log$)' }
if ($taskUnexpected) { throw 'Source tree contains private/runtime files.' }
$taskPackage = Get-Content -LiteralPath (Join-Path $taskApp 'package.json') -Raw | ConvertFrom-Json
$taskNative = [IO.File]::ReadAllText((Join-Path $taskApp 'launcher\DomTrack.cs'))
$taskOuter = [IO.File]::ReadAllText((Join-Path $taskApp 'launcher\SingleFile.cs'))
$taskServer = [IO.File]::ReadAllText((Join-Path $taskApp 'server.cjs'))
$taskExpectedVersion = $taskPackage.version+'.0'
foreach ($taskSource in @($taskNative,$taskOuter)) {
    if ($taskSource -notmatch ('AssemblyFileVersion\("'+[Regex]::Escape($taskExpectedVersion)+'"\)')) { throw 'package.json and native file versions must agree before publishing.' }
}
if ($taskServer -notmatch ("APP_VERSION='"+[Regex]::Escape($taskPackage.version)+"'")) { throw 'Server and package versions disagree.' }
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
foreach ($taskDirectory in @('lib','public','runtime')) { Copy-Item -LiteralPath (Join-Path $taskApp $taskDirectory) -Destination (Join-Path $taskOutput $taskDirectory) -Recurse }
foreach ($taskFile in @('server.cjs','package.json','DomTrack-UA.exe.config')) { Copy-Item -LiteralPath (Join-Path $taskApp $taskFile) -Destination (Join-Path $taskOutput $taskFile) }
$taskRepositoryRoot = [IO.Path]::GetDirectoryName($taskApp.TrimEnd('\','/'))
foreach ($taskNoticeName in @('LICENSE','THIRD_PARTY_NOTICES.md','PRIVACY.md')) { $taskNoticePath = Join-Path $taskRepositoryRoot $taskNoticeName; if ([IO.File]::Exists($taskNoticePath)) { Copy-Item -LiteralPath $taskNoticePath -Destination (Join-Path $taskOutput $taskNoticeName) } }
$taskCompiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not [IO.File]::Exists($taskCompiler)) { throw '.NET Framework compiler is unavailable on this runner.' }
$taskSdk = Join-Path $taskOutput 'runtime\webview2'
& $taskCompiler /nologo /target:winexe /platform:x64 /optimize+ ('/out:'+(Join-Path $taskOutput 'DomTrack-UA.exe')) ('/win32icon:'+(Join-Path $taskApp 'public\app.ico')) /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Security.dll /reference:System.Web.Extensions.dll ('/reference:'+(Join-Path $taskSdk 'Microsoft.Web.WebView2.Core.dll')) ('/reference:'+(Join-Path $taskSdk 'Microsoft.Web.WebView2.WinForms.dll')) (Join-Path $taskApp 'launcher\DomTrack.cs') (Join-Path $taskApp 'launcher\AppWindow.cs') (Join-Path $taskApp 'launcher\WindowsBridge.cs')
if ($LASTEXITCODE -ne 0) { throw 'Native source compilation failed.' }
$taskStatus = "UNSIGNED BUILD INPUT - NOT AN INSTALLABLE RELEASE`nThis folder is source/build evidence for a later trusted signing or Store submission process. Source compilation does not provide signing approval, a trusted signature or runtime acceptance. No protection settings should be changed to run this unsigned launcher.`n"
[IO.File]::WriteAllText((Join-Path $taskOutput 'BUILD-STATUS.txt'),$taskStatus,[Text.UTF8Encoding]::new($false))
$taskLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependency-lock.json') -Raw | ConvertFrom-Json
$taskProvenance = [ordered]@{version=$taskPackage.version;commit=$env:GITHUB_SHA;repository=$env:GITHUB_REPOSITORY;runnerImage=$env:ImageOS;runnerImageVersion=$env:ImageVersion;compilerVersion=(Get-Item -LiteralPath $taskCompiler).VersionInfo.FileVersion;dependencies=$taskLock;release=$false;signed=$false;nativeWindowsHelper=$true;nativeLauncherSha256=(Get-FileHash -LiteralPath (Join-Path $taskOutput 'DomTrack-UA.exe') -Algorithm SHA256).Hash.ToLowerInvariant();guiExecuted=$false;integrationTestsNotRun=@('Real Windows CurrentUser DPAPI','Windows system notification','Native WebView2 GUI and tray')}
[IO.File]::WriteAllText((Join-Path $taskOutput 'ci-provenance.json'),($taskProvenance | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
$taskLines = foreach ($taskFile in (Get-ChildItem -LiteralPath $taskOutput -Recurse -File | Sort-Object FullName)) { (Get-FileHash -LiteralPath $taskFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()+"`t"+$taskFile.Length+"`t"+$taskFile.FullName.Substring($taskOutput.Length+1).Replace('\','/') }
[IO.File]::WriteAllText((Join-Path $taskOutput 'files.sha256'),($taskLines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
Write-Output 'Unsigned source CI folder compiled; no application, native test helper or GUI was executed.'
