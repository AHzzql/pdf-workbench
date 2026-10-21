#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$directory = Join-Path $PSScriptRoot ('../backend/target/init-probe-' + [Guid]::NewGuid().ToString('N'))
$directory = [IO.Path]::GetFullPath($directory)
$environment = Join-Path $directory '.env.local'
$acl = Join-Path $directory 'runtime/redis-users.acl'
$identity = Join-Path $directory 'runtime/s3-identities.json'
$security = Join-Path $directory 'runtime/security.toml'
function Initialize { $null = & (Join-Path $PSScriptRoot 'Initialize-Local.ps1') -Directory $directory }
function Must-Fail([scriptblock] $Action) {
    $failed = $false
    try { & $Action } catch { $failed = $true }
    if (!$failed) { throw 'Initialization accepted inconsistent credentials.' }
}
try {
    Initialize
    $first = [IO.File]::ReadAllText($environment)
    $aclHash = (Get-FileHash -LiteralPath $acl).Hash
    $hashes = @($environment,$acl,$identity,$security | ForEach-Object { (Get-FileHash -LiteralPath $_).Hash })
    Initialize
    $again = @($environment,$acl,$identity,$security | ForEach-Object { (Get-FileHash -LiteralPath $_).Hash })
    if (Compare-Object $hashes $again) { throw 'Repeated initialization rotated configuration.' }
    $core = @($first -split "`n" | Where-Object { $_ -match '^(POSTGRES|REDIS|ELASTIC|RABBITMQ)_PASSWORD=' })
    [IO.File]::WriteAllText($environment, ($core -join "`n") + "`n")
    Remove-Item -LiteralPath $identity,$security
    Initialize
    $updated = [IO.File]::ReadAllText($environment)
    foreach ($line in $core) { if (!$updated.Contains($line)) { throw 'Existing service credentials changed.' } }
    if ((Get-FileHash -LiteralPath $acl).Hash -ne $aclHash) { throw 'Redis ACL changed during upgrade.' }
    [IO.File]::AppendAllText($environment, "S3_ACCESS_KEY=duplicate`n")
    Must-Fail { Initialize }
    [IO.File]::WriteAllText($environment, $updated)
    [IO.File]::AppendAllText($identity, 'changed')
    $changed = [IO.File]::ReadAllText($identity)
    Must-Fail { Initialize }
    if ([IO.File]::ReadAllText($identity) -cne $changed) { throw 'Existing object storage configuration was overwritten.' }
    Remove-Item -LiteralPath $acl
    Must-Fail { Initialize }
    Write-Output 'PASS initialization, repeatability, credential upgrade and inconsistent-state rejection'
} finally {
    foreach ($path in @($environment,$acl,$identity,$security)) { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path } }
    foreach ($path in @((Join-Path $directory 'runtime'),$directory)) { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path } }
}
