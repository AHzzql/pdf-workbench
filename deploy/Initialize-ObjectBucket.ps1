#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$arguments = @('compose', '--env-file', (Join-Path $PSScriptRoot '.env.local'), '-f', (Join-Path $PSScriptRoot 'compose.yml'))
$result = "s3.bucket.create -name pdf-workbench-private -owner workbench`nexit" |
    & docker @arguments exec -T objects weed shell '-master=127.0.0.1:9333' '-filer=127.0.0.1:8888' 2>&1
$text = $result -join "`n"
if (!(($LASTEXITCODE -eq 0 -and $text -match '(?m)^created bucket pdf-workbench-private\s*$') -or
    ($LASTEXITCODE -eq 1 -and $text -match '(?m)^error: bucket pdf-workbench-private already exists\s*$'))) {
    throw 'Private bucket initialization failed.'
}
$result = 's3.bucket.list' | & docker @arguments exec -T objects weed shell '-master=127.0.0.1:9333' '-filer=127.0.0.1:8888' 2>&1
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch '(?m)^\s*pdf-workbench-private\s+.*owner:"workbench"\s*$') {
    throw 'Private bucket ownership differs from the configured identity.'
}
$result = "fs.configure -locationPrefix=/buckets/ -volumeGrowthCount=1 -fsync=true -apply`nexit" |
    & docker @arguments exec -T objects weed shell '-master=127.0.0.1:9333' '-filer=127.0.0.1:8888' 2>&1
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -match '(?im)^.*(error|failed)') {
    throw 'Private bucket volume configuration failed.'
}
Write-Output 'Private bucket is ready.'
