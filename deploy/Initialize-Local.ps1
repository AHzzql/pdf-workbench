#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$runtime = Join-Path $PSScriptRoot 'runtime'
$environmentFile = Join-Path $PSScriptRoot '.env.local'
$aclFile = Join-Path $runtime 'redis-users.acl'
if ((Test-Path -LiteralPath $environmentFile) -or (Test-Path -LiteralPath $aclFile)) {
    if (!(Test-Path -LiteralPath $environmentFile) -or !(Test-Path -LiteralPath $aclFile)) {
        throw 'Local credentials are incomplete.'
    }
    Write-Output 'Local credentials already exist.'
    return
}
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$values = [ordered]@{}
foreach ($name in @('POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'ELASTIC_PASSWORD', 'RABBITMQ_PASSWORD')) {
    $values[$name] = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
}
$hash = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($values.REDIS_PASSWORD))).ToLowerInvariant()
[IO.File]::WriteAllText($aclFile, "user default off`nuser workbench on #$hash ~pdf-workbench:* &* +@all -@dangerous`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllLines($environmentFile, @($values.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }), [Text.UTF8Encoding]::new($false))
Write-Output 'Local credentials created.'
