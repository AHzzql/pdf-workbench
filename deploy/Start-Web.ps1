#requires -Version 7.2
param(
    [int] $Port = 8080,
    [ValidateSet('web','worker')][string] $Role = 'web',
    [string] $TaskNamespace = 'pdf-workbench.tasks.v1',
    [ValidateRange(128,16384)][int] $MaxHeapMB = 512,
    [string] $DataDirectory,
    [string] $DatabaseSchema,
    [string] $SessionNamespace = 'pdf-workbench:session:v1',
    [switch] $EnableModel
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ApplicationProcess.ps1')
$process = [Diagnostics.Process]::Start((New-WorkbenchStartInfo @PSBoundParameters))
try {
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Application process failed.' }
} finally { $process.Dispose() }
