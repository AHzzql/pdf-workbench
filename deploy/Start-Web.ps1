#requires -Version 7.2
param(
    [int] $Port = 8080,
    [ValidateRange(128,16384)][int] $MaxHeapMB = 512,
    [string] $DataDirectory,
    [string] $DatabaseSchema,
    [string] $SessionNamespace = 'pdf-workbench:session:v1',
    [switch] $EnableModel
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'backend/target/pdf-workbench-1.1.0.jar'
if (!$env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 21 or later.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (!(Test-Path $java) -or !(Test-Path $jar)) { throw 'JDK and packaged application are required.' }
if (!$DataDirectory) { $DataDirectory = Join-Path $PSScriptRoot 'runtime/data' }
$DataDirectory = [IO.Path]::GetFullPath($DataDirectory)
$names = @('DATABASE_PASSWORD','POSTGRES_PASSWORD','REDIS_PASSWORD','ELASTIC_PASSWORD','DATABASE_URL')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local')) {
        $pair = $line -split '=', 2
        if ($pair.Count -eq 2 -and $pair[0] -in @('POSTGRES_PASSWORD','REDIS_PASSWORD','ELASTIC_PASSWORD')) {
            [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process')
        }
    }
    $env:DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
    $static = [Uri]::new((Join-Path $root 'frontend/dist') + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
    $arguments = @("-Xmx${MaxHeapMB}m", '-jar', $jar, "--spring.web.resources.static-locations=$static", "--server.port=$Port", "--ai.data-directory=$DataDirectory", "--spring.session.redis.namespace=$SessionNamespace")
    if ($DatabaseSchema) {
        if ($DatabaseSchema -notmatch '^[a-z][a-z0-9_]{0,62}$') { throw 'Invalid database schema.' }
        $env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:15432/pdf_workbench?currentSchema=$DatabaseSchema"
        $arguments += @("--spring.flyway.schemas=$DatabaseSchema", "--spring.flyway.default-schema=$DatabaseSchema")
    }
    if (!$EnableModel) {
        $arguments += @('--spring.config.import=optional:classpath:local-no-provider.yml','--ai.api-key=','--ai.base-url=http://127.0.0.1:9','--ai.rerank-url=http://127.0.0.1:9')
    }
    & $java @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Native Web process failed.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
