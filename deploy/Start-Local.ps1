#requires -Version 7.2
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Initialize-Local.ps1')
if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot '../frontend/dist/index.html'))) {
    throw 'Build frontend assets before starting the gateway.'
}
$arguments = @('compose', '--env-file', (Join-Path $PSScriptRoot '.env.local'), '-f', (Join-Path $PSScriptRoot 'compose.yml'))
& docker @arguments config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Invalid middleware configuration.' }
& docker @arguments up --detach --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { throw 'Middleware startup failed.' }
Write-Output 'Local gateway: http://127.0.0.1:5173'
