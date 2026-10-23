#requires -Version 7.2
param([Parameter(Mandatory)][string] $DeliveryDirectory)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$directory=[IO.Path]::GetFullPath($DeliveryDirectory)
$runtime=Get-Content -LiteralPath (Join-Path $directory 'runtime-verification.json') -Raw | ConvertFrom-Json
if(!$runtime.passed){throw 'Packaged executable verification must pass first.'}
$stage=[IO.Path]::GetFullPath($runtime.extractedRoot)
$allowed=[IO.Path]::GetFullPath((Join-Path $root 'backend/target/delivery-check'))+[IO.Path]::DirectorySeparatorChar
if(!$stage.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Source verification stage is outside delivery-check.'}
if((Test-Path -LiteralPath (Join-Path $stage 'backend/target/classes')) -or (Test-Path -LiteralPath (Join-Path $stage 'frontend/node_modules'))){throw 'Source verification requires a fresh extraction.'}
$credential=Join-Path $stage 'deploy/.env.local'
if(Test-Path -LiteralPath $credential){throw 'Fresh extraction contains an unexpected credential file.'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$result=[ordered]@{passed=$false;extractedRoot=$stage;runtimeJarSHA256=$runtime.jarSHA256;backendTests=0;frontendTests=0;sourceClassParity=$false;frontendArtifactParity=$false;temporaryCredentialsRemoved=$false}
try {
    New-Item -ItemType File -Path $credential -ErrorAction Stop | Out-Null
    Set-Acl -LiteralPath $credential -AclObject (Get-Acl -LiteralPath (Join-Path $root 'deploy/.env.local'))
    [IO.File]::WriteAllText($credential,[IO.File]::ReadAllText((Join-Path $root 'deploy/.env.local')))
    Push-Location (Join-Path $stage 'backend')
    try {
        & mvn.cmd package *> (Join-Path $directory 'source-backend.log')
        if($LASTEXITCODE -ne 0){throw 'Extracted backend build or tests failed.'}
    } finally {Pop-Location}
    $reports=@(Get-ChildItem -LiteralPath (Join-Path $stage 'backend/target/surefire-reports') -Filter 'TEST-*.xml' -File)
    if(!$reports.Count){throw 'Extracted backend test reports are missing.'}
    foreach($report in $reports) {
        [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
        $suite=$xml.testsuite
        if([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0){throw 'Extracted backend reports contain unsuccessful tests.'}
        $result.backendTests += [int]$suite.tests
    }
    if($result.backendTests -lt 1){throw 'No extracted backend tests executed.'}
    $jar=[IO.Compression.ZipFile]::OpenRead((Join-Path $directory 'runtime-original.jar'))
    try {
        $checked=0
        foreach($entry in $jar.Entries) {
            if(!$entry.FullName.StartsWith('BOOT-INF/classes/') -or $entry.FullName.EndsWith('/')){continue}
            $relative=$entry.FullName.Substring('BOOT-INF/classes/'.Length)
            if($relative -match '(\\|:|(^|/)\.\.?(/|$))'){throw 'Unsafe compiled resource path.'}
            $file=Join-Path (Join-Path $stage 'backend/target/classes') $relative
            if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw 'Packaged class is missing from extracted source build.'}
            $stream=$entry.Open()
            try {$digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}
            finally {$stream.Dispose()}
            if($digest -cne (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Packaged class differs from extracted source build.'}
            $checked++
        }
        if(!$checked -or $checked -ne @(Get-ChildItem -LiteralPath (Join-Path $stage 'backend/target/classes') -Recurse -File).Count){throw 'Compiled class/resource sets differ.'}
        $result.sourceClassParity=$true
        $result.compiledResources=$checked
    } finally {$jar.Dispose()}
    Push-Location (Join-Path $stage 'frontend')
    try {
        & npm.cmd ci --offline --no-audit --no-fund *> (Join-Path $directory 'source-frontend-install.log')
        if($LASTEXITCODE -ne 0){throw 'Extracted frontend offline dependency installation failed.'}
        & npm.cmd run lint *> (Join-Path $directory 'source-frontend-lint.log')
        if($LASTEXITCODE -ne 0){throw 'Extracted frontend lint failed.'}
        $testReport=Join-Path $directory 'source-frontend-tests.json'
        & npm.cmd run test -- --reporter=json "--outputFile=$testReport" *> (Join-Path $directory 'source-frontend-tests.log')
        if($LASTEXITCODE -ne 0){throw 'Extracted frontend tests failed.'}
        $tests=Get-Content -LiteralPath $testReport -Raw | ConvertFrom-Json
        if(!$tests.success -or $tests.numFailedTests -ne 0 -or $tests.numPendingTests -ne 0 -or $tests.numTodoTests -ne 0 -or $tests.numTotalTests -lt 1){throw 'Extracted frontend test result is unsuccessful.'}
        $result.frontendTests=$tests.numTotalTests
        & npm.cmd run build *> (Join-Path $directory 'source-frontend-build.log')
        if($LASTEXITCODE -ne 0){throw 'Extracted frontend type check or production build failed.'}
    } finally {Pop-Location}
    $archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $directory 'PDF_Extractor_PartB_Delivery.zip'))
    try {
        $checked=0
        foreach($entry in $archive.Entries) {
            if(!$entry.FullName.StartsWith('PDF_Extractor_PartB/frontend/dist/') -or $entry.FullName.EndsWith('/')){continue}
            $relative=$entry.FullName.Substring('PDF_Extractor_PartB/frontend/dist/'.Length)
            $file=Join-Path (Join-Path $stage 'frontend/dist') $relative
            if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw 'Packaged frontend asset is missing from source build.'}
            $stream=$entry.Open()
            try {$digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}
            finally {$stream.Dispose()}
            if($digest -cne (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Packaged frontend asset differs from source build.'}
            $checked++
        }
        if(!$checked -or $checked -ne @(Get-ChildItem -LiteralPath (Join-Path $stage 'frontend/dist') -Recurse -File).Count){throw 'Frontend asset sets differ.'}
        $result.frontendArtifactParity=$true
        $result.frontendAssets=$checked
    } finally {$archive.Dispose()}
    $result.passed=$true
    Write-Output "PASS extracted source: $($result.backendTests) backend tests, $($result.frontendTests) frontend tests, lint, production build and original-JAR class parity"
} finally {
    try {
        if(Test-Path -LiteralPath $credential){Remove-Item -LiteralPath $credential -Force}
        $result.temporaryCredentialsRemoved=!(Test-Path -LiteralPath $credential)
    } finally {
        if(!$result.temporaryCredentialsRemoved){$result.passed=$false}
        $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'source-preflight.json') -Encoding utf8
    }
}
