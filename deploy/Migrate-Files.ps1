#requires -Version 7.2
param([Parameter(Mandatory)][string] $SourceDirectory, [string] $DatabaseSchema)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar = Join-Path $root 'backend/target/pdf-workbench-1.1.0.jar'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar)) { throw 'JDK and packaged application are required.' }
$source = (Resolve-Path -LiteralPath $SourceDirectory).Path
if (!(Test-Path -LiteralPath $source -PathType Container)) { throw 'A source directory is required.' }
if ($DatabaseSchema -and $DatabaseSchema -notmatch '^[a-z][a-z0-9_]{0,62}$') { throw 'Invalid database schema.' }
$names = @('POSTGRES_PASSWORD','DATABASE_PASSWORD','DATABASE_URL','S3_ACCESS_KEY','S3_SECRET_KEY')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local')) {
        $pair = $line -split '=',2
        if ($pair.Count -eq 2 -and $pair[0] -in @('POSTGRES_PASSWORD','S3_ACCESS_KEY','S3_SECRET_KEY')) {
            [Environment]::SetEnvironmentVariable($pair[0],$pair[1],'Process')
        }
    }
    $env:DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
    if ($DatabaseSchema) { $env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:15432/pdf_workbench?currentSchema=$DatabaseSchema" }
    & $java '-Dloader.main=cn.edu.pdfworkbench.storage.MigrateDocumentFiles' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher $source
    if ($LASTEXITCODE -ne 0) { throw 'Document file migration failed.' }
} finally {
    foreach ($name in $names) {
        if ($null -eq $previous[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    }
}
