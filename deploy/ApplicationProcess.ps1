#requires -Version 7.2
function New-WorkbenchStartInfo {
    param(
        [int] $Port = 8080,
        [ValidateSet('web','worker')][string] $Role = 'web',
        [string] $TaskNamespace = 'pdf-workbench.tasks.v1',
        [ValidateRange(128,16384)][int] $MaxHeapMB = 512,
        [string] $DataDirectory,
        [string] $DatabaseSchema,
        [string] $SessionNamespace = 'pdf-workbench:session:v1',
        [switch] $EnableModel,
        [string] $ReadyFile
    )
    $root = Split-Path -Parent $PSScriptRoot
    $jar = Join-Path $root 'backend/target/pdf-workbench-1.1.0.jar'
    if (!$env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 21 or later.' }
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar)) { throw 'JDK and packaged application are required.' }
    if (!$DataDirectory) { $DataDirectory = Join-Path $PSScriptRoot "runtime/$Role-files" }
    $info = [Diagnostics.ProcessStartInfo]::new($java)
    $info.UseShellExecute = $false
    $info.WorkingDirectory = $root
    $names = @('POSTGRES_PASSWORD','REDIS_PASSWORD','ELASTIC_PASSWORD','RABBITMQ_PASSWORD','S3_ACCESS_KEY','S3_SECRET_KEY')
    $values = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local')) {
        $pair = $line -split '=', 2
        if ($pair.Count -eq 2 -and $pair[0] -in $names) {
            if ($values.ContainsKey($pair[0])) { throw 'Duplicate local credential name.' }
            $values[$pair[0]] = $pair[1]
        }
    }
    foreach ($name in $names) {
        if (!$values[$name]) { throw 'Local credentials are incomplete.' }
        $info.Environment[$name] = $values[$name]
    }
    $info.Environment['DATABASE_PASSWORD'] = $values.POSTGRES_PASSWORD
    $static = [Uri]::new((Join-Path $root 'frontend/dist') + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
    foreach ($value in @("-Xmx${MaxHeapMB}m", '-jar', $jar, "--spring.web.resources.static-locations=$static", "--server.port=$Port",
        "--ai.data-directory=$([IO.Path]::GetFullPath($DataDirectory))", "--spring.session.redis.namespace=$SessionNamespace",
        "--tasks.namespace=$TaskNamespace", "--tasks.role=$Role")) { $info.ArgumentList.Add($value) }
    if ($Role -eq 'worker') {
        $info.ArgumentList.Add('--spring.main.web-application-type=none')
        $info.ArgumentList.Add('--spring.main.keep-alive=true')
    }
    if ($DatabaseSchema) {
        if ($DatabaseSchema -notmatch '^[a-z][a-z0-9_]{0,62}$') { throw 'Invalid database schema.' }
        $info.Environment['DATABASE_URL'] = "jdbc:postgresql://127.0.0.1:15432/pdf_workbench?currentSchema=$DatabaseSchema"
        $info.ArgumentList.Add("--spring.flyway.schemas=$DatabaseSchema")
        $info.ArgumentList.Add("--spring.flyway.default-schema=$DatabaseSchema")
    }
    if (!$EnableModel) {
        foreach ($value in @('--spring.config.import=optional:classpath:local-no-provider.yml','--ai.api-key=',
            '--ai.base-url=http://127.0.0.1:9','--ai.rerank-url=http://127.0.0.1:9')) { $info.ArgumentList.Add($value) }
    }
    if ($ReadyFile) {
        $info.ArgumentList.Add('--deployment.managed=true')
        $info.ArgumentList.Add("--deployment.ready-file=$ReadyFile")
        $info.RedirectStandardInput = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.CreateNoWindow = $true
    }
    return $info
}
