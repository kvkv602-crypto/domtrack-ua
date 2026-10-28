$ErrorActionPreference = 'Stop'
try {
    [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $taskInput = New-Object System.Text.StringBuilder
    $taskBuffer = New-Object char[] 4096
    while (($taskRead = [Console]::In.Read($taskBuffer, 0, $taskBuffer.Length)) -gt 0) {
        [void]$taskInput.Append($taskBuffer, 0, $taskRead)
        if ($taskInput.Length -gt 65536) { throw 'Invalid input' }
    }
    $taskRequest = $taskInput.ToString() | ConvertFrom-Json
    if ($null -eq $taskRequest -or $taskRequest.value -isnot [string] -or $taskRequest.operation -notin @('protect', 'unprotect')) { throw 'Invalid operation' }
    if (@($taskRequest.PSObject.Properties.Name | Where-Object { $_ -notin @('operation', 'value') }).Count -ne 0) { throw 'Invalid input' }
    Add-Type -AssemblyName System.Security
    $taskEntropy = [Text.Encoding]::UTF8.GetBytes('DomTrackUA/key-vault/v1/CurrentUser')
    if ($taskRequest.operation -eq 'protect') {
        if ($taskRequest.value.Length -lt 1 -or $taskRequest.value.Length -gt 500 -or $taskRequest.value -match "[`r`n`0]") { throw 'Invalid key' }
        $taskPlain = [Text.Encoding]::UTF8.GetBytes($taskRequest.value)
        try { $taskCipher = [Security.Cryptography.ProtectedData]::Protect($taskPlain, $taskEntropy, [Security.Cryptography.DataProtectionScope]::CurrentUser) }
        finally { if ($null -ne $taskPlain) { [Array]::Clear($taskPlain, 0, $taskPlain.Length) } }
        $taskValue = [Convert]::ToBase64String($taskCipher)
    } else {
        if ($taskRequest.value.Length -gt 64512) { throw 'Invalid cipher' }
        $taskCipher = [Convert]::FromBase64String($taskRequest.value)
        $taskPlain = [Security.Cryptography.ProtectedData]::Unprotect($taskCipher, $taskEntropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        try { $taskValue = [Text.Encoding]::UTF8.GetString($taskPlain) }
        finally { if ($null -ne $taskPlain) { [Array]::Clear($taskPlain, 0, $taskPlain.Length) } }
        if ($taskValue.Length -lt 1 -or $taskValue.Length -gt 500 -or $taskValue -match "[`r`n`0]") { throw 'Invalid key' }
    }
    [Console]::Out.Write((@{ ok = $true; value = $taskValue } | ConvertTo-Json -Compress))
    exit 0
} catch {
    [Console]::Error.WriteLine('Key vault operation failed.')
    exit 1
}
