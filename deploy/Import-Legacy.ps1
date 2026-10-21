#requires -Version 7.2
param(
    [Parameter(Mandatory)][string] $AccountSource,
    [Parameter(Mandatory)][string] $ReadingSource,
    [Parameter(Mandatory)][string] $FileTarget
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar = Join-Path $root 'backend/target/pdf-workbench-1.1.0.jar'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar)) { throw 'JDK and packaged application are required.' }
$previous = $env:POSTGRES_PASSWORD
try {
    foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local')) {
        $pair = $line -split '=', 2
        if ($pair.Count -eq 2 -and $pair[0] -eq 'POSTGRES_PASSWORD') { $env:POSTGRES_PASSWORD = $pair[1] }
    }
    & $java '-Dloader.main=cn.edu.pdfworkbench.storage.LegacyDataImport' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher $AccountSource $ReadingSource $FileTarget
    if ($LASTEXITCODE -ne 0) { throw 'Legacy import failed.' }
} finally { $env:POSTGRES_PASSWORD = $previous }
