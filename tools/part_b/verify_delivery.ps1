#requires -Version 7.2
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$stage=Join-Path $root 'backend/target/delivery-check/PDF_Extractor_PartB'
$directory=Join-Path $root 'backend/target/part-b-delivery'
$values=@{}
foreach($line in Get-Content -LiteralPath (Join-Path $root 'deploy/.env.local')){
    $pair=$line -split '=',2
    if($pair.Count -eq 2){$values[$pair[0]]=$pair[1]}
}
$compose=@('compose','--env-file',(Join-Path $root 'deploy/.env.local'),'-f',(Join-Path $root 'deploy/compose.yml'))
function Sql([string]$query){
    $result=& docker @compose exec -T postgres psql -U pdf_workbench -d pdf_workbench -At -v ON_ERROR_STOP=1 -c $query 2>&1
    if($LASTEXITCODE -ne 0){throw 'Delivery schema operation failed.'}
    return $result
}
$schema='wb_delivery_'+[Guid]::NewGuid().ToString('N')
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$listener.Start();$port=$listener.LocalEndpoint.Port;$listener.Stop()
$process=$null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $directory 'PDF_Extractor_PartB_Delivery.zip'))
    try {
        $entry=$archive.GetEntry('PDF_Extractor_PartB/backend/target/pdf-workbench-1.1.0.jar')
        $jar=Join-Path $directory 'runtime-original.jar'
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$jar,$true)
    } finally {$archive.Dispose()}
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=Join-Path $env:JAVA_HOME 'bin/java.exe'
    $info.UseShellExecute=$false
    $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true
    $info.RedirectStandardError=$true
    $info.WorkingDirectory=$stage
    foreach($argument in @('-Xmx384m','-jar',$jar,"--server.port=$port",
        "--spring.flyway.schemas=$schema","--spring.flyway.default-schema=$schema",
        '--spring.config.import=optional:classpath:local-no-provider.yml','--ai.api-key=',
        '--ai.base-url=http://127.0.0.1:9','--ai.rerank-url=http://127.0.0.1:9',
        "--ai.data-directory=$(Join-Path $directory 'runtime-files')",
        "--spring.web.resources.static-locations=$([Uri]::new((Join-Path $stage 'frontend/dist')+[IO.Path]::DirectorySeparatorChar).AbsoluteUri)",
        '--tasks.role=web',("--tasks.namespace=pdf-workbench.delivery."+$schema.Replace('_','-')),
        "--spring.session.redis.namespace=pdf-workbench:delivery:$schema")){
        $info.ArgumentList.Add($argument)
    }
    $info.Environment['DATABASE_URL']="jdbc:postgresql://127.0.0.1:15432/pdf_workbench?currentSchema=$schema"
    $info.Environment['DATABASE_PASSWORD']=$values.POSTGRES_PASSWORD
    foreach($name in @('REDIS_PASSWORD','ELASTIC_PASSWORD','RABBITMQ_PASSWORD','S3_ACCESS_KEY','S3_SECRET_KEY')){
        $info.Environment[$name]=$values[$name]
    }
    $process=[Diagnostics.Process]::Start($info)
    $stdout=$process.StandardOutput.ReadToEndAsync()
    $stderr=$process.StandardError.ReadToEndAsync()
    $base="http://127.0.0.1:$port"
    $ready=$false
    for($i=0;$i -lt 90;$i++){
        if($process.HasExited){throw 'Delivery executable exited before readiness.'}
        try{
            $page=Invoke-WebRequest -Uri "$base/" -TimeoutSec 2
            if($page.StatusCode -eq 200 -and $page.Content -match '<div id="app">'){$ready=$true;break}
        }catch{}
        Start-Sleep -Milliseconds 500
    }
    if(!$ready){throw 'Delivery executable readiness timeout.'}
    $contract=Invoke-RestMethod -Uri "$base/v3/api-docs" -TimeoutSec 10
    if($contract.openapi -ne '3.0.3'){throw 'Executable OpenAPI probe failed.'}
    $file=Get-Item -LiteralPath (Join-Path $stage 'docs/part-b/evidence/api-sample.pdf')
    $text=Invoke-RestMethod -Uri "$base/api/v1/pdf/text" -Method Post -Form @{file=$file} -TimeoutSec 20
    $json=$text | ConvertTo-Json -Depth 20 -Compress
    if($json -notmatch 'Alpha' -or $json -notmatch '42'){throw 'Executable text extraction probe failed.'}
    $bad=Invoke-WebRequest -Uri "$base/api/v1/pdf/text/search" -Method Post -Form @{file=$file;pattern='['} -SkipHttpErrorCheck -TimeoutSec 20
    if($bad.StatusCode -ne 400){throw 'Executable error boundary probe failed.'}
    [ordered]@{passed=$true;static=$true;openapi=$true;pdfText=$true;invalidPatternStatus=400;jarSHA256=(Get-FileHash -LiteralPath $jar).Hash.ToLower()} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'runtime-verification.json') -Encoding utf8
    Write-Output 'PASS packaged executable: independent JVM, extracted frontend, OpenAPI, PDF text and invalid-pattern boundary'
} finally {
    if($process){
        if(!$process.HasExited){$process.Kill($true);$process.WaitForExit()}
        [IO.File]::WriteAllText((Join-Path $directory 'runtime.log'),$stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult())
    }
    if($schema -notmatch '^wb_delivery_[a-f0-9]{32}$'){throw 'Invalid delivery schema'}
    $null=Sql "DROP SCHEMA IF EXISTS $schema CASCADE"
}
