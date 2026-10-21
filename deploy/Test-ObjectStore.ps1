#requires -Version 7.2
param([switch] $Restart)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar = Join-Path $root 'backend/target/pdf-workbench-1.1.0.jar'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar)) { throw 'JDK and packaged application are required.' }
$directory = Join-Path $root ('backend/target/object-probe-' + [Guid]::NewGuid().ToString('N'))
$arguments = @('compose','--env-file',(Join-Path $PSScriptRoot '.env.local'),'-f',(Join-Path $PSScriptRoot 'compose.yml'))
$previous = @{}
foreach ($name in @('S3_ACCESS_KEY','S3_SECRET_KEY','S3_ENDPOINT','S3_BUCKET','S3_REGION')) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Invoke-Probe([string] $Action) {
    & $java '-Dloader.main=cn.edu.pdfworkbench.storage.ObjectStoreProbe' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher $Action $directory
    if ($LASTEXITCODE -ne 0) { throw 'Private storage probe failed.' }
}
try {
    $env:S3_ENDPOINT = 'http://127.0.0.1:18333'
    $env:S3_BUCKET = 'pdf-workbench-private'
    $env:S3_REGION = 'us-east-1'
    foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local')) {
        $pair = $line -split '=', 2
        if ($pair.Count -eq 2 -and $pair[0] -in @('S3_ACCESS_KEY','S3_SECRET_KEY')) { [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process') }
    }
    $id = & docker @arguments ps --quiet objects
    if ($LASTEXITCODE -ne 0 -or !$id) { throw 'Object storage container is not running.' }
    $bindings = & docker inspect --format '{{json .HostConfig.PortBindings}}' $id | ConvertFrom-Json
    $ports = @($bindings.psobject.Properties)
    if ($ports.Count -ne 1 -or $ports[0].Name -ne '8333/tcp' -or $ports[0].Value.Count -ne 1 -or
        $ports[0].Value[0].HostIp -ne '127.0.0.1' -or $ports[0].Value[0].HostPort -ne '18333') {
        throw 'Object storage published ports differ from the loopback boundary.'
    }
    $networks = & docker inspect --format '{{json .NetworkSettings.Networks}}' $id | ConvertFrom-Json
    $network = @($networks.psobject.Properties)
    if ($network.Count -ne 1 -or $network[0].Name -ne 'pdf-workbench-local_object_storage') { throw 'Object storage shares an unexpected network.' }
    $members = & docker network inspect --format '{{len .Containers}}' $network[0].Name
    $interContainer = & docker network inspect --format '{{index .Options "com.docker.network.bridge.enable_icc"}}' $network[0].Name
    if ($members -ne '1' -or $interContainer -ne 'false') { throw 'Object storage network isolation differs from deployment configuration.' }
    Write-Output 'PASS private storage loopback port and dedicated bridge boundary'
    Invoke-Probe 'write'
    if ($Restart) {
        & docker @arguments restart --no-deps objects
        if ($LASTEXITCODE -ne 0) { throw 'Object storage restart failed.' }
        & docker @arguments up --detach --wait --wait-timeout 120 objects
        if ($LASTEXITCODE -ne 0) { throw 'Object storage recovery failed.' }
    }
    Invoke-Probe 'verify'
} finally {
    try {
        if (Test-Path -LiteralPath (Join-Path $directory 'manifest.json')) { Invoke-Probe 'delete' }
    } finally {
        foreach ($name in $previous.Keys) {
            if ($null -eq $previous[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
            else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        }
    }
}
