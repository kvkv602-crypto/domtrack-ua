#Requires -Version 7.0
param([Parameter(Mandatory)][string]$Executable, [Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'

# PEReader inspects bytes only. Never load or execute the inspected assembly.
$taskInput = [IO.File]::OpenRead([IO.Path]::GetFullPath($Executable))
$taskPe = $null
try {
    $taskPe = [System.Reflection.PortableExecutable.PEReader]::new($taskInput)
    if (-not $taskPe.HasMetadata -or $null -eq $taskPe.PEHeaders.CorHeader) { throw 'В EXE отсутствуют CLR metadata.' }
    $taskMetadata = [System.Reflection.Metadata.PEReaderExtensions]::GetMetadataReader($taskPe)
    $taskDirectory = $taskPe.PEHeaders.CorHeader.ResourcesDirectory
    if ($taskDirectory.RelativeVirtualAddress -le 0 -or $taskDirectory.Size -le 0 -or $taskDirectory.Size -gt 513MB) { throw 'Некорректная секция ресурсов.' }
    $taskBlock = $taskPe.GetSectionData($taskDirectory.RelativeVirtualAddress)
    if ($taskDirectory.Size -gt $taskBlock.Length) { throw 'Секция ресурсов выходит за пределы PE.' }
    $taskFound = [Collections.Generic.Dictionary[string,byte[]]]::new([StringComparer]::Ordinal)
    foreach ($taskHandle in $taskMetadata.ManifestResources) {
        $taskResource = $taskMetadata.GetManifestResource($taskHandle)
        $taskName = $taskMetadata.GetString($taskResource.Name)
        if ($taskName -cnotin @('DomTrack.Payload', 'DomTrack.Manifest')) { throw 'Неожиданный именованный ресурс.' }
        if (-not $taskResource.Implementation.IsNil -or $taskFound.ContainsKey($taskName)) { throw 'Ресурс не встроен или повторяется.' }
        [long]$taskOffset = $taskResource.Offset
        if ($taskOffset -lt 0 -or $taskOffset -gt $taskDirectory.Size - 4) { throw 'Некорректное смещение ресурса.' }
        $taskLengthReader = $taskBlock.GetReader([int]$taskOffset, 4)
        [long]$taskLength = $taskLengthReader.ReadUInt32()
        if ($taskLength -gt $taskDirectory.Size - $taskOffset - 4 -or $taskLength -gt 512MB) { throw 'Ресурс выходит за пределы секции.' }
        $taskDataReader = $taskBlock.GetReader([int]($taskOffset + 4), [int]$taskLength)
        [byte[]]$taskData = $taskDataReader.ReadBytes([int]$taskLength)
        $taskFound.Add($taskName, $taskData)
    }
    if ($taskFound.Count -ne 2) { throw 'Не найден полный комплект ресурсов.' }
    $taskDestination = [IO.Path]::GetFullPath($Destination)
    if ([IO.Directory]::Exists($taskDestination)) { throw 'Папка ресурсов уже существует.' }
    [IO.Directory]::CreateDirectory($taskDestination) | Out-Null
    foreach ($taskName in @('DomTrack.Payload', 'DomTrack.Manifest')) {
        [IO.File]::WriteAllBytes([IO.Path]::Combine($taskDestination, $taskName), $taskFound[$taskName])
    }
    Write-Output 'READ PE/CLR package resources as data; no assembly loaded or executed.'
}
finally {
    if ($null -ne $taskPe) { $taskPe.Dispose() }
    $taskInput.Dispose()
}
