#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$run = [Guid]::NewGuid().ToString('N')
$schema = 'wb_file_' + $run
$namespace = 'pdf-workbench.test.' + $run
$directory = Join-Path $root ('backend/target/file-recovery-' + $run)
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar = Join-Path $root 'backend/target/pdf-workbench-1.1.0.jar'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar)) { throw 'JDK and packaged application are required.' }
$compose = @('compose','--env-file',(Join-Path $PSScriptRoot '.env.local'),'-f',(Join-Path $PSScriptRoot 'compose.yml'))
$values = @{}
Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local') | ForEach-Object {
    $pair = $_ -split '=',2
    if ($pair.Count -eq 2) { $values[$pair[0]]=$pair[1] }
}
$previous = @{}
foreach ($name in @('S3_ACCESS_KEY','S3_SECRET_KEY','S3_ENDPOINT','S3_REGION','S3_BUCKET','RABBITMQ_PASSWORD')) {
    $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
}
function Sql([string] $Statement) {
    $result = & docker @compose exec -T postgres psql -U pdf_workbench -d pdf_workbench -v ON_ERROR_STOP=1 -At -c $Statement 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'File recovery database probe failed.' }
    return ($result -join "`n").Trim()
}
function Probe([string] $Action) {
    & $java '-Dloader.main=cn.edu.pdfworkbench.storage.ObjectStoreProbe' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher $Action $directory
    if ($LASTEXITCODE -ne 0) { throw 'File recovery object probe failed.' }
}
$worker=$null
try {
    $env:S3_ACCESS_KEY=$values.S3_ACCESS_KEY; $env:S3_SECRET_KEY=$values.S3_SECRET_KEY
    $env:S3_ENDPOINT='http://127.0.0.1:18333'; $env:S3_REGION='us-east-1'; $env:S3_BUCKET='pdf-workbench-private'
    Probe 'write'
    $manifest = Get-Content -LiteralPath (Join-Path $directory 'manifest.json') -Raw | ConvertFrom-Json
    $owner=$manifest.key.workspace; $document=$manifest.key.document; $generation=$manifest.key.generation
    foreach ($id in @($owner,$document,$generation)) { if ($id -notmatch '^[a-f0-9-]{36}$') { throw 'Invalid file fixture identity.' } }
    if ($manifest.sha256 -notmatch '^[a-f0-9]{64}$' -or $manifest.bytes -lt 1 -or $manifest.bytes -gt 33554432) { throw 'Invalid file fixture manifest.' }
    $null=Sql "CREATE SCHEMA $schema"
    $info=[Diagnostics.ProcessStartInfo]::new($java)
    $info.WorkingDirectory=$root; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach ($argument in @('-Xmx256m','-jar',$jar,'--spring.main.web-application-type=none','--spring.main.keep-alive=true','--tasks.role=worker',
        "--tasks.namespace=$namespace","--spring.flyway.schemas=$schema","--spring.flyway.default-schema=$schema",'--spring.datasource.hikari.maximum-pool-size=3',
        '--storage.cleanup-interval-seconds=1',"--ai.data-directory=$directory/worker-files",'--spring.config.import=optional:classpath:local-no-provider.yml','--ai.api-key=')) {
        $info.ArgumentList.Add($argument)
    }
    $info.Environment['DATABASE_URL']="jdbc:postgresql://127.0.0.1:15432/pdf_workbench?currentSchema=$schema"
    $info.Environment['DATABASE_PASSWORD']=$values.POSTGRES_PASSWORD
    foreach ($name in @('REDIS_PASSWORD','ELASTIC_PASSWORD','RABBITMQ_PASSWORD')) { $info.Environment[$name]=$values[$name] }
    $worker=[Diagnostics.Process]::Start($info)
    $output=$worker.StandardOutput.ReadToEndAsync(); $errorOutput=$worker.StandardError.ReadToEndAsync()
    $ready=$false
    for ($i=0;$i -lt 30;$i++) {
        if ($worker.HasExited) { throw 'File recovery Worker exited before readiness.' }
        if ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema' AND table_name='document_files'") -eq '1') { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'File recovery schema readiness timeout.' }
    $null=Sql "INSERT INTO $schema.document_files(workspace_id,document_id,generation,bucket,state,pdf_bytes,pdf_sha256,content_bytes,content_sha256,lease_until) VALUES('$owner','$document','$generation','pdf-workbench-private','STAGING',1,repeat('0',64),$($manifest.bytes),'$($manifest.sha256)',clock_timestamp()-interval '1 second')"
    $collected=$false
    for ($i=0;$i -lt 30;$i++) {
        if ($worker.HasExited) { throw 'File recovery Worker exited during cleanup.' }
        if ((Sql "SELECT COUNT(*) FROM $schema.document_files WHERE generation='$generation' AND state='GARBAGE' AND cleanup_token IS NULL AND cleanup_at>clock_timestamp()") -eq '1') { $collected=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$collected) { throw 'Expired upload was not reconciled by the Worker.' }
    Probe 'missing'
    Write-Output 'PASS independent Worker retires expired upload, removes the partial S3 object and retains the cleanup tombstone'
} finally {
    try {
        if ($worker) {
            if (!$worker.HasExited) { $worker.Kill($true); $worker.WaitForExit() }
            [IO.File]::WriteAllText((Join-Path $directory 'worker.log'),$output.GetAwaiter().GetResult()+$errorOutput.GetAwaiter().GetResult())
        }
        if (Test-Path -LiteralPath (Join-Path $directory 'manifest.json')) { Probe 'delete' }
        $env:RABBITMQ_PASSWORD=$values.RABBITMQ_PASSWORD
        & $java ("-Dloader.path="+(Join-Path $root 'backend/target/test-classes')) '-Dloader.main=cn.edu.pdfworkbench.TaskNativeFixture' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher cleanup $namespace
        if ($LASTEXITCODE -ne 0) { throw 'File recovery queue cleanup failed.' }
    } finally {
        try {
            if ($schema -notmatch '^wb_file_[a-f0-9]{32}$') { throw 'Invalid file recovery schema.' }
            $null=Sql "DROP SCHEMA IF EXISTS $schema CASCADE"
        } finally {
            foreach ($name in $previous.Keys) {
                if ($null -eq $previous[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
                else { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
            }
        }
    }
}
