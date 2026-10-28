$ErrorActionPreference = 'Stop'
try {
  [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
  $taskInput = New-Object System.Text.StringBuilder
  $taskBuffer = New-Object char[] 1024
  while (($taskRead = [Console]::In.Read($taskBuffer, 0, $taskBuffer.Length)) -gt 0) {
    [void]$taskInput.Append($taskBuffer, 0, $taskRead)
    if ($taskInput.Length -gt 4096) { throw 'Invalid input' }
  }
  $taskNotice = $taskInput.ToString() | ConvertFrom-Json
  if ($null -eq $taskNotice -or $taskNotice.title -isnot [string] -or $taskNotice.text -isnot [string] -or $taskNotice.title.Length -gt 120 -or $taskNotice.text.Length -gt 240) { throw 'Invalid input' }
  if (@($taskNotice.PSObject.Properties.Name | Where-Object { $_ -notin @('title', 'text') }).Count -ne 0) { throw 'Invalid input' }
  Add-Type -AssemblyName System.Windows.Forms
  Add-Type -AssemblyName System.Drawing
  $taskIcon = New-Object System.Windows.Forms.NotifyIcon
  $taskIcon.Icon = [System.Drawing.SystemIcons]::Information
  $taskIcon.Visible = $true
  $taskIcon.ShowBalloonTip(10000, [string]$taskNotice.title, [string]$taskNotice.text, [System.Windows.Forms.ToolTipIcon]::Info)
  Start-Sleep -Seconds 11
  $taskIcon.Dispose()
} catch { exit 1 }
