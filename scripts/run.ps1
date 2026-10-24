[CmdletBinding()]
param([string]$JavaPath)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jarPath = Join-Path $projectRoot 'build\input\wordnest.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}
if ($JavaPath) {
    if (Test-Path -LiteralPath $JavaPath -PathType Container) {
        $JavaPath = Join-Path $JavaPath 'bin\javaw.exe'
    }
} elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) {
    $JavaPath = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
} else {
    $javaCommand = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $JavaPath = $javaCommand.Source }
}
if (-not $JavaPath -or -not (Test-Path -LiteralPath $JavaPath)) {
    throw 'Java 17 or newer is required for the source launcher. Use the portable 词巢.exe package, or pass -JavaPath <JDK/JRE folder>.'
}
$javaConsole = Join-Path (Split-Path $JavaPath -Parent) 'java.exe'
if (Test-Path -LiteralPath $javaConsole) {
    $version = (& $javaConsole --version | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the selected Java runtime version.' }
    if ($version -match '(?m)^(?:openjdk|java)(?: version)?\s+"?(\d+)') {
        if ([int]$Matches[1] -lt 17) { throw '词巢 requires Java 17 or newer.' }
    }
}
Start-Process -FilePath $JavaPath -ArgumentList @('-Xmx512m', '-Dfile.encoding=UTF-8', '-jar', ('"' + $jarPath + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden
