#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$arguments = @('compose', '--env-file', (Join-Path $PSScriptRoot '.env.local'), '-f', (Join-Path $PSScriptRoot 'compose.yml'))
$values = @{}
Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local') | ForEach-Object {
    $pair = $_ -split '=', 2
    if ($pair.Count -eq 2) { $values[$pair[0]] = $pair[1] }
}
function Invoke-Component([string[]] $Command) {
    $result = & docker @arguments exec -T @Command 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Component probe failed.' }
    return ($result -join "`n").Trim()
}
$postgres = Invoke-Component @('postgres', 'psql', '-U', 'pdf_workbench', '-d', 'pdf_workbench', '-v', 'ON_ERROR_STOP=1', '-At', '-c', 'BEGIN; CREATE TEMP TABLE deployment_probe(value integer); INSERT INTO deployment_probe VALUES (42); SELECT value FROM deployment_probe; ROLLBACK;')
if ($postgres -notmatch '(?m)^42$') { throw 'PostgreSQL transaction probe failed.' }
Write-Output 'PASS PostgreSQL transaction'
$key = 'pdf-workbench:probe:' + [Guid]::NewGuid().ToString('N')
try {
    $redis = Invoke-Component @('redis', 'redis-cli', '--user', 'workbench', 'set', $key, 'deployment-probe', 'EX', '30')
    if ($redis -ne 'OK') { throw 'Redis write probe failed.' }
    $redis = Invoke-Component @('redis', 'redis-cli', '--user', 'workbench', 'get', $key)
    if ($redis -ne 'deployment-probe') { throw 'Redis read probe failed.' }
    $outside = 'forbidden-probe:' + [Guid]::NewGuid().ToString('N')
    $denied = Invoke-Component @('redis', 'redis-cli', '--user', 'workbench', 'set', $outside, 'denied', 'EX', '30')
    if ($denied -notmatch 'NOPERM') { throw 'Redis ACL probe failed.' }
    Write-Output 'PASS Redis authenticated read/write and namespace ACL'
} finally {
    $null = Invoke-Component @('redis', 'redis-cli', '--user', 'workbench', 'del', $key)
}
$null = Invoke-Component @('rabbitmq', 'rabbitmq-diagnostics', '-q', 'check_running')
Write-Output 'PASS RabbitMQ running'
$base = 'http://127.0.0.1:19200'
$denied = Invoke-WebRequest -Uri $base -SkipHttpErrorCheck -TimeoutSec 10
if ($denied.StatusCode -ne 401) { throw 'Elasticsearch authentication probe failed.' }
$authorization = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('elastic:' + $values.ELASTIC_PASSWORD))
$headers = @{ Authorization = 'Basic ' + $authorization }
function Invoke-Search([string] $Method, [string] $Path, $Body = $null) {
    $request = @{ Uri = $base + $Path; Method = $Method; Headers = $headers; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $request.Body = $Body | ConvertTo-Json -Depth 20 -Compress
        $request.ContentType = 'application/json; charset=utf-8'
    }
    return Invoke-RestMethod @request
}
$index = 'pdf-workbench-probe-' + [Guid]::NewGuid().ToString('N')
$created = $false
try {
    $null = Invoke-Search 'PUT' "/$index" @{
        settings = @{ number_of_shards = 1; number_of_replicas = 0 }
        mappings = @{ properties = @{
            workspace = @{ type = 'keyword' }
            text = @{ type = 'text'; analyzer = 'cjk' }
            vector = @{ type = 'dense_vector'; dims = 1024; index = $true; similarity = 'cosine' }
        } }
    }
    $created = $true
    $a = [float[]]::new(1024); $a[0] = 1
    $b = [float[]]::new(1024); $b[1] = 1
    $null = Invoke-Search 'PUT' "/$index/_doc/a?refresh=wait_for" @{ workspace = 'space-a'; text = 'PDF 文档检索'; vector = $a }
    $null = Invoke-Search 'PUT' "/$index/_doc/b?refresh=wait_for" @{ workspace = 'space-b'; text = 'PDF 文档检索'; vector = $b }
    $result = Invoke-Search 'POST' "/$index/_search" @{ query = @{ bool = @{
        filter = @(@{ term = @{ workspace = 'space-a' } })
        must = @(@{ match = @{ text = '文档' } })
    } } }
    if ($result.hits.hits.Count -ne 1 -or $result.hits.hits[0]._id -ne 'a') { throw 'Elasticsearch CJK/filter probe failed.' }
    $result = Invoke-Search 'POST' "/$index/_search" @{ knn = @{
        field = 'vector'; query_vector = $a; k = 2; num_candidates = 5
        filter = @{ term = @{ workspace = 'space-b' } }
    } }
    if ($result.hits.hits.Count -ne 1 -or $result.hits.hits[0]._id -ne 'b') { throw 'Elasticsearch vector/filter probe failed.' }
    Write-Output 'PASS Elasticsearch authentication, CJK and filtered 1024-dimensional vectors'
} finally {
    if ($created) { $null = Invoke-Search 'DELETE' "/$index" }
}
$gateway = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/' -TimeoutSec 10
if ($gateway.StatusCode -ne 200 -or $gateway.Content -notmatch '<div id="app">') { throw 'Gateway static asset probe failed.' }
$session = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/api/v1/account/session' -TimeoutSec 10
if (!$session.workspaceId) { throw 'Gateway API probe failed.' }
Write-Output 'PASS Nginx static assets and native Windows API proxy'
$hostCheck = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/' -Headers @{ Host = 'untrusted.example' } -SkipHttpErrorCheck -TimeoutSec 10
if ($hostCheck.StatusCode -ne 400) { throw 'Gateway host policy probe failed.' }
Write-Output 'PASS Nginx host allowlist'
$worker = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '../frontend/dist/assets') -Filter 'pdf.worker.*.mjs')
if ($worker.Count -ne 1) { throw 'PDF worker asset missing or ambiguous.' }
$workerResponse = Invoke-WebRequest -Method Head -Uri ('http://127.0.0.1:5173/assets/' + $worker[0].Name) -TimeoutSec 10
if ($workerResponse.Headers['Content-Type'] -notmatch '^(application|text)/javascript') { throw 'PDF worker MIME probe failed.' }
Write-Output 'PASS Nginx PDF worker JavaScript MIME'
$schema = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/v3/api-docs' -TimeoutSec 15
if ($schema.openapi -ne '3.0.3') { throw 'Gateway OpenAPI probe failed.' }
$swagger = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/swagger-ui/index.html' -TimeoutSec 10
if ($swagger.Content -notmatch 'swagger-ui') { throw 'Gateway Swagger probe failed.' }
Write-Output 'PASS Nginx OpenAPI and Swagger routes'
$part = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/part-a/' -TimeoutSec 10
$contract = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/part-a/openapi.json' -TimeoutSec 10
if ($part.StatusCode -ne 200 -or $part.Content -notmatch 'Part A 需求规格说明书' -or $contract.servers[0].url -ne '/' -or @($contract.paths.psobject.Properties).Count -ne 11) {
    throw 'Part A same-origin prototype probe failed.'
}
$missing = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/part-a/missing-artifact' -SkipHttpErrorCheck -TimeoutSec 10
$hidden = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/part-a/.env' -SkipHttpErrorCheck -TimeoutSec 10
$lock = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/part-a/~$document.docx' -SkipHttpErrorCheck -TimeoutSec 10
if ($missing.StatusCode -ne 404 -or $hidden.StatusCode -ne 403 -or $lock.StatusCode -ne 403) { throw 'Part A artifact boundary probe failed.' }
$document = Get-Item -LiteralPath (Join-Path $PSScriptRoot '../docs/part-a/PartA需求规格说明书.docx')
$download = Invoke-WebRequest -Uri ('http://127.0.0.1:5173/part-a/' + [Uri]::EscapeDataString($document.Name)) -TimeoutSec 10
$downloadHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($download.RawContentStream.ToArray()))
if ($downloadHash -ne (Get-FileHash -LiteralPath $document.FullName -Algorithm SHA256).Hash) { throw 'Part A document download differs from the generated artifact.' }
$english = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/part-a/submission/' -TimeoutSec 10
$englishContract = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/part-a/submission/openapi.json' -TimeoutSec 10
if ($english.Content -notmatch 'English submission document' -or $englishContract.servers[0].url -ne '/' -or @($englishContract.paths.psobject.Properties).Count -ne 11) { throw 'English submission prototype probe failed.' }
$englishDoc = Get-Item -LiteralPath (Join-Path $PSScriptRoot '../docs/part-a/submission/PartA_Submission.docx')
$englishDownload = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/part-a/submission/PartA_Submission.docx' -TimeoutSec 10
$englishHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($englishDownload.RawContentStream.ToArray()))
if ($englishHash -ne (Get-FileHash -LiteralPath $englishDoc.FullName -Algorithm SHA256).Hash) { throw 'English submission download differs from the verified document.' }
Write-Output 'PASS Nginx Part A same-origin prototype and artifact boundaries'
