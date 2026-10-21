#requires -Version 7.2
param(
    [ValidateRange(128,16384)][int] $MaxHeapMB = 512,
    [string] $DataDirectory,
    [string] $DatabaseSchema,
    [string] $TaskNamespace = 'pdf-workbench.tasks.v1',
    [switch] $EnableModel
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Start-Web.ps1') -Role worker @PSBoundParameters
