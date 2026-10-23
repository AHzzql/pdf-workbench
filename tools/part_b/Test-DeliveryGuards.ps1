#requires -Version 7.2
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$directory=Join-Path $root ('backend/target/delivery-guard-tests/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Assert([bool]$Value,[string]$Message){if(!$Value){throw $Message}}
function Zip([string]$Path,$Entries) {
    $archive=[IO.Compression.ZipFile]::Open($Path,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach($entry in $Entries) {
            $item=$archive.CreateEntry($entry.name)
            $stream=$item.Open()
            try {$bytes=[Text.Encoding]::UTF8.GetBytes($entry.text);$stream.Write($bytes,0,$bytes.Length)}
            finally {$stream.Dispose()}
        }
    } finally {$archive.Dispose()}
}
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes('fixture'))).ToLowerInvariant()
$manifest=@{format='pdf-extractor-part-b-delivery-v1';files=@(
    @{path='README.md';bytes=7;sha256=$hash},@{path='README.md';bytes=7;sha256=$hash})} | ConvertTo-Json -Depth 5
$cases=@(
    @{name='duplicate-manifest';message='Duplicate or self-referencing';entries=@(
        @{name='PDF_Extractor_PartB/README.md';text='fixture'},
        @{name='PDF_Extractor_PartB/workbench.cmd';text='fixture'},
        @{name='PDF_Extractor_PartB/MANIFEST.json';text=$manifest})},
    @{name='zip-traversal';message='Unsafe or duplicate';entries=@(
        @{name='PDF_Extractor_PartB/../outside.txt';text='fixture'})})
foreach($case in $cases) {
    $folder=Join-Path $directory $case.name
    New-Item -ItemType Directory -Path $folder | Out-Null
    Zip (Join-Path $folder 'PDF_Extractor_PartB_Delivery.zip') $case.entries
    '{"passed":true}' | Set-Content -LiteralPath (Join-Path $folder 'runtime-verification.json')
    $rejected=$false
    try {& (Join-Path $PSScriptRoot 'verify_delivery.ps1') -DeliveryDirectory $folder}
    catch {$rejected=$_.Exception.Message -match $case.message}
    Assert $rejected "Invalid archive accepted: $($case.name)"
    $result=Get-Content -LiteralPath (Join-Path $folder 'runtime-verification.json') -Raw | ConvertFrom-Json
    Assert (!$result.passed) 'Failed verification retained a stale PASS result.'
    Write-Output "PASS $($case.name) rejected and stale result invalidated"
}
$outside=Join-Path $directory 'outside-stage'
New-Item -ItemType Directory -Path $outside | Out-Null
@{passed=$true;extractedRoot=$root} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outside 'runtime-verification.json')
$rejected=$false
try {& (Join-Path $PSScriptRoot 'verify_source.ps1') -DeliveryDirectory $outside}
catch {$rejected=$_.Exception.Message -match 'outside delivery-check'}
Assert $rejected 'Outside source directory was accepted.'
Write-Output 'PASS source directory confinement'
