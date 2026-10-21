#requires -Version 7.2
param([string] $Directory = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$runtime = Join-Path $Directory 'runtime'
$environmentFile = Join-Path $Directory '.env.local'
$aclFile = Join-Path $runtime 'redis-users.acl'
$values = [ordered]@{}
if ((Test-Path -LiteralPath $environmentFile) -or (Test-Path -LiteralPath $aclFile)) {
    if (!(Test-Path -LiteralPath $environmentFile) -or !(Test-Path -LiteralPath $aclFile)) {
        throw 'Local credentials are incomplete.'
    }
    foreach ($line in Get-Content -LiteralPath $environmentFile) {
        $pair = $line -split '=', 2
        if ($pair.Count -eq 2 -and !$pair[0].StartsWith('#')) {
            if ($values.Contains($pair[0])) { throw 'Duplicate local credential name.' }
            $values[$pair[0]] = $pair[1]
        }
    }
    foreach ($name in @('POSTGRES_PASSWORD','REDIS_PASSWORD','ELASTIC_PASSWORD','RABBITMQ_PASSWORD')) {
        if (!$values[$name]) { throw 'Local credentials are incomplete.' }
    }
}
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$newValues = [Collections.Generic.List[string]]::new()
foreach ($name in @('POSTGRES_PASSWORD','REDIS_PASSWORD','ELASTIC_PASSWORD','RABBITMQ_PASSWORD',
    'S3_ACCESS_KEY','S3_SECRET_KEY','WEED_VOLUME_WRITE_KEY','WEED_VOLUME_READ_KEY','WEED_FILER_WRITE_KEY','WEED_FILER_READ_KEY')) {
    if (!$values.Contains($name)) {
        $length = if ($name -eq 'S3_ACCESS_KEY') { 16 } else { 32 }
        $values[$name] = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes($length)).ToLowerInvariant()
        $newValues.Add("$name=$($values[$name])")
    } elseif (!$values[$name]) { throw 'Local credentials are incomplete.' }
}
if (!(Test-Path -LiteralPath $aclFile)) {
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($values.REDIS_PASSWORD))).ToLowerInvariant()
    [IO.File]::WriteAllText($aclFile, "user default off`nuser workbench on #$hash ~pdf-workbench:* &* +@all -@dangerous`n", [Text.UTF8Encoding]::new($false))
}
if ($newValues.Count -gt 0) {
    $prefix = if (Test-Path -LiteralPath $environmentFile) { [IO.File]::ReadAllText($environmentFile).TrimEnd("`r", "`n") + "`n" } else { '' }
    [IO.File]::WriteAllText($environmentFile, $prefix + ($newValues -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
foreach ($name in @('S3_ACCESS_KEY','S3_SECRET_KEY','WEED_VOLUME_WRITE_KEY','WEED_VOLUME_READ_KEY','WEED_FILER_WRITE_KEY','WEED_FILER_READ_KEY')) {
    $length = if ($name -eq 'S3_ACCESS_KEY') { 32 } else { 64 }
    if ($values[$name] -cnotmatch "^[a-f0-9]{$length}$") { throw 'Invalid local object storage credential.' }
}
$identity = [ordered]@{ identities = @([ordered]@{ name = 'workbench'; credentials = @([ordered]@{
    accessKey = $values.S3_ACCESS_KEY; secretKey = $values.S3_SECRET_KEY
}); actions = @('Read:pdf-workbench-private','Write:pdf-workbench-private','List:pdf-workbench-private') }) }
$security = @"
[jwt.signing]
key = "$($values.WEED_VOLUME_WRITE_KEY)"
expires_after_seconds = 30
[jwt.signing.read]
key = "$($values.WEED_VOLUME_READ_KEY)"
expires_after_seconds = 30
[jwt.filer_signing]
key = "$($values.WEED_FILER_WRITE_KEY)"
expires_after_seconds = 30
[jwt.filer_signing.read]
key = "$($values.WEED_FILER_READ_KEY)"
expires_after_seconds = 30
[access]
ui = false
[filer.expose_directory_metadata]
enabled = false
"@
foreach ($item in @(@{ Name = 's3-identities.json'; Content = ($identity | ConvertTo-Json -Depth 8) },
    @{ Name = 'security.toml'; Content = $security })) {
    $path = Join-Path $runtime $item.Name
    if (Test-Path -LiteralPath $path) {
        if ([IO.File]::ReadAllText($path) -cne $item.Content) { throw 'Object storage configuration differs from local credentials.' }
    } else { [IO.File]::WriteAllText($path, $item.Content, [Text.UTF8Encoding]::new($false)) }
}
Write-Output 'Local credentials and private storage configuration are ready.'
