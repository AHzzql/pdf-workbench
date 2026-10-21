#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$run = [Guid]::NewGuid().ToString('N')
$schema = 'wb_native_' + $run
$directory = Join-Path $root ('backend/target/persistence-' + $run)
New-Item -ItemType Directory -Path $directory, (Join-Path $directory 'tmp') -Force | Out-Null
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar = Join-Path $root 'backend/target/pdf-workbench-1.1.0.jar'
$fixture = Join-Path $root 'backend/target/verification/api-sample.pdf'
if (!(Test-Path $jar) -or !(Test-Path $fixture)) { throw 'Packaged application and PDF fixture required.' }
$credentials = @{}
Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.local') | ForEach-Object {
    $pair = $_ -split '=', 2
    if ($pair.Count -eq 2) { $credentials[$pair[0]] = $pair[1] }
}
$processes = [Collections.Generic.List[object]]::new()
function Start-Node([int] $Port, [int] $RedisPort = 16379, [switch] $Worker) {
    if (!$Worker) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
        try { $listener.Start() } finally { $listener.Stop() }
    }
    $info = [Diagnostics.ProcessStartInfo]::new($java)
    $info.WorkingDirectory = $root
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in @('-Xmx256m','-jar',$jar,"--server.port=$Port","--spring.data.redis.port=$RedisPort",'--spring.config.import=optional:classpath:native-probe.yml',
        "--spring.flyway.schemas=$schema","--spring.flyway.default-schema=$schema",'--spring.datasource.hikari.maximum-pool-size=3',
        "--spring.session.redis.namespace=pdf-workbench:test:$run","--tasks.namespace=pdf-workbench.test.$run",'--tasks.max-attempts=3','--tasks.lease-seconds=5','--tasks.timeout-seconds=120',"--search.alias=pdf-workbench-test-$run","--ai.data-directory=$directory/files",
        "--workbench.temporary-root=$directory/tmp",'--ai.api-key=fixture','--ai.base-url=http://127.0.0.1:8189','--ai.rerank-url=http://127.0.0.1:8189/rerank')) { $info.ArgumentList.Add($argument) }
    if (!$Worker) { $info.ArgumentList.Add('--spring.rabbitmq.port=9') }
    if ($Worker) { $info.ArgumentList.Add('--spring.main.web-application-type=none'); $info.ArgumentList.Add('--spring.main.keep-alive=true'); $info.ArgumentList.Add('--tasks.role=worker') }
    if ($RedisPort -eq 9) { $info.ArgumentList.Add('--search.url=http://127.0.0.1:9') }
    $info.Environment['DATABASE_URL'] = "jdbc:postgresql://127.0.0.1:15432/pdf_workbench?currentSchema=$schema"
    $info.Environment['DATABASE_PASSWORD'] = $credentials.POSTGRES_PASSWORD
    $info.Environment['REDIS_PASSWORD'] = $credentials.REDIS_PASSWORD
    $info.Environment['ELASTIC_PASSWORD'] = $credentials.ELASTIC_PASSWORD
    $info.Environment['RABBITMQ_PASSWORD'] = $credentials.RABBITMQ_PASSWORD
    $process = [Diagnostics.Process]::Start($info)
    $node = [pscustomobject]@{ Process = $process; Output = $process.StandardOutput.ReadToEndAsync(); Error = $process.StandardError.ReadToEndAsync(); Port = $Port }
    $processes.Add($node)
    if ($Worker) { return $node }
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ($process.HasExited) { throw 'Native node exited before readiness.' }
        try { $null = Invoke-RestMethod "http://127.0.0.1:$Port/v3/api-docs" -TimeoutSec 2; $ready = $true; break }
        catch { Start-Sleep -Milliseconds 500 }
    }
    if (!$ready) { throw 'Native node readiness timeout.' }
    return $node
}
function Start-Fixture {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 8189)
    try { $listener.Start() } finally { $listener.Stop() }
    $info = [Diagnostics.ProcessStartInfo]::new($java)
    $info.WorkingDirectory=$root; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach($argument in @('-Xmx128m',("-Dloader.path="+(Join-Path $root 'backend/target/test-classes')),'-Dloader.main=cn.edu.pdfworkbench.TaskNativeFixture','-cp',$jar,'org.springframework.boot.loader.launch.PropertiesLauncher','model','8189')) { $info.ArgumentList.Add($argument) }
    $process=[Diagnostics.Process]::Start($info)
    $node=[pscustomobject]@{Process=$process;Output=$process.StandardOutput.ReadToEndAsync();Error=$process.StandardError.ReadToEndAsync();Port=8189}
    $processes.Add($node)
    for($i=0;$i -lt 40;$i++) {
        if($process.HasExited){throw 'Model fixture exited before readiness.'}
        try { $null=Invoke-RestMethod 'http://127.0.0.1:8189/control' -TimeoutSec 2; return $node }
        catch {Start-Sleep -Milliseconds 200}
    }
    throw 'Model fixture readiness timeout.'
}
function Hold-Model([bool]$Hold) {
    $null=Invoke-RestMethod 'http://127.0.0.1:8189/control' -Method Post -ContentType 'application/json' -Body (@{hold=$Hold}|ConvertTo-Json -Compress)
}
function Await-Message($Session,[int]$Port,[string]$Id,[switch]$Partial) {
    for($i=0;$i -lt 900;$i++) {
        $message=(Request $Session $Port GET "/api/v1/pdf/ai/runs/$Id").Content|ConvertFrom-Json
        if($Partial) {
            if($message.status -ne 'RUNNING'){throw 'Generation terminated before a partial checkpoint.'}
            if($message.text.Length -gt 0){return $message}
        } elseif($message.status -ne 'RUNNING'){return $message}
        Start-Sleep -Milliseconds 100
    }
    $diagnostic=& docker compose --env-file (Join-Path $PSScriptRoot '.env.local') -f (Join-Path $PSScriptRoot 'compose.yml') exec -T postgres psql -U pdf_workbench -d pdf_workbench -At -c "SELECT json_agg(s) FROM (SELECT kind,state,attempt,fence,stage,error_code,lease_until,deadline FROM $schema.work_tasks ORDER BY created_at) s;"
    [IO.File]::WriteAllText((Join-Path $directory 'task-diagnostic.json'),($diagnostic -join "`n"))
    $outbox=& docker compose --env-file (Join-Path $PSScriptRoot '.env.local') -f (Join-Path $PSScriptRoot 'compose.yml') exec -T postgres psql -U pdf_workbench -d pdf_workbench -At -c "SELECT json_agg(s) FROM (SELECT route,attempts,available_at,lease_until,published_at FROM $schema.task_outbox ORDER BY id) s;"
    [IO.File]::WriteAllText((Join-Path $directory 'outbox-diagnostic.json'),($outbox -join "`n"))
    foreach($node in $processes | Where-Object {$_.Port -eq 0 -and !$_.Process.HasExited}) {
        & (Join-Path $env:JAVA_HOME 'bin/jcmd.exe') $node.Process.Id Thread.print | Set-Content -LiteralPath (Join-Path $directory ('worker-threads-'+$node.Process.Id+'.txt'))
    }
    $control=Invoke-RestMethod 'http://127.0.0.1:8189/control'
    throw "Persistent task did not reach the expected state: partial=$Partial status=$($message.status) textLength=$($message.text.Length) activeProvider=$($control.activeChats)"

}
function Stop-Node($Node) {
    if (!$Node.Process.HasExited) { $Node.Process.Kill($true); $Node.Process.WaitForExit() }
    [IO.File]::WriteAllText((Join-Path $directory ("node-" + $Node.Port + '-' + $Node.Process.Id + '.log')), $Node.Output.Result + $Node.Error.Result)
}
function Request($Session, [int] $Port, [string] $Method, [string] $Path, $Body = $null) {
    $options = @{ Uri = "http://127.0.0.1:$Port$Path"; WebSession = $Session; Method = $Method; TimeoutSec = 15; SkipHttpErrorCheck = $true }
    if ($Method -notin @('GET','HEAD')) {
        $token = Invoke-RestMethod "http://127.0.0.1:$Port/api/v1/account/csrf" -WebSession $Session -TimeoutSec 10
        $options.Headers = @{ $token.headerName = $token.token }
    }
    if ($null -ne $Body) { $options.ContentType = 'application/json'; $options.Body = $Body | ConvertTo-Json -Compress }
    return Invoke-WebRequest @options
}
function Upload($Session, [int] $Port) {
    $token = Invoke-RestMethod "http://127.0.0.1:$Port/api/v1/account/csrf" -WebSession $Session -TimeoutSec 10
    $response = Invoke-WebRequest "http://127.0.0.1:$Port/api/v1/pdf/ai/documents" -Method Post -WebSession $Session -Headers @{ $token.headerName = $token.token } -Form @{file = Get-Item -LiteralPath $fixture} -TimeoutSec 20
    return ($response.Content | ConvertFrom-Json).id
}
try {
    $model = Start-Fixture
    $a = Start-Node 8180
    $b = Start-Node 8181
    $guest = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $guestSpace = (Request $guest 8180 GET '/api/v1/account/session').Content | ConvertFrom-Json
    $same = (Request $guest 8181 GET '/api/v1/account/session').Content | ConvertFrom-Json
    if ($same.workspaceId -ne $guestSpace.workspaceId) { throw 'Guest workspace differs across nodes.' }
    $guestDocument = Upload $guest 8180
    if ((Request $guest 8181 GET "/api/v1/pdf/ai/documents/$guestDocument/file").StatusCode -ne 200) { throw 'Guest file not shared across nodes.' }
    $other = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    if ((Request $other 8181 GET "/api/v1/pdf/ai/documents/$guestDocument/file").StatusCode -ne 404) { throw 'Guest ownership isolation failed.' }
    Write-Output 'PASS independent native nodes share only the same guest session data'
    $account = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $email = "native-$run@example.com"
    $password = 'probe-' + [Guid]::NewGuid().ToString('N')
    if ((Request $account 8180 POST '/api/v1/account/register' @{email=$email;displayName='Persistence probe';password=$password}).StatusCode -ne 201) { throw 'Registration failed.' }
    if ((Request $account 8180 POST '/api/v1/account/login' @{email=$email;password=$password}).StatusCode -ne 200) { throw 'Login failed.' }
    $identity = (Request $account 8181 GET '/api/v1/account/profile').Content | ConvertFrom-Json
    $document = Upload $account 8180
    $chat = (Request $account 8181 POST '/api/v1/pdf/ai/conversations' @{documentId=$document}).Content | ConvertFrom-Json
    $messageId = [Guid]::NewGuid().ToString()
    $message = Request $account 8180 POST "/api/v1/pdf/ai/conversations/$($chat.id)/messages" @{requestId=$messageId;mode='ASK';question='Native persistence probe'}
    if ($message.StatusCode -ne 200) { throw 'Message submission failed.' }
    $pending=(Request $account 8181 GET "/api/v1/pdf/ai/runs/$messageId").Content|ConvertFrom-Json
    if($pending.status -ne 'RUNNING'){throw 'Task was not durable while publishers could not reach the broker.'}
    Stop-Node $a
    $worker=Start-Node 0 -Worker
    $finished=Await-Message $account 8181 $messageId
    if($finished.status -ne 'COMPLETED' -or $finished.citations.Count -ne 1){throw ("Independent worker pipeline: status=$($finished.status), citations=$($finished.citations.Count), error=$($finished.error)")}
    $a=Start-Node 8180
    Write-Output 'PASS committed task survives submitting Web process termination and broker-unreachable Web nodes'
    if ((Request $guest 8181 GET "/api/v1/pdf/ai/documents/$document").StatusCode -ne 404) { throw 'Account ownership isolation failed.' }
    if ((Request $account 8181 POST '/api/v1/account/logout').StatusCode -ne 204) { throw 'Logout failed.' }
    if ((Request $account 8180 GET '/api/v1/account/profile').StatusCode -ne 401) { throw 'Remote logout did not revoke the session.' }
    $null = Request $account 8180 POST '/api/v1/account/login' @{email=$email;password=$password}
    $second = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $null = Request $second 8181 POST '/api/v1/account/login' @{email=$email;password=$password}
    $newPassword = 'updated-' + [Guid]::NewGuid().ToString('N')
    if ((Request $account 8180 POST '/api/v1/account/password' @{currentPassword=$password;newPassword=$newPassword}).StatusCode -ne 204) { throw 'Password update failed.' }
    if ((Request $second 8181 GET '/api/v1/account/profile').StatusCode -ne 401) { throw 'Credential version revocation failed.' }
    $null = Request $account 8181 POST '/api/v1/account/login' @{email=$email;password=$newPassword}
    Write-Output 'PASS shared accounts, conversation, logout and credential revocation'
    $rateEmail = "rate-$run@example.com"
    for ($i=0; $i -lt 5; $i++) {
        $port = if ($i % 2 -eq 0) { 8180 } else { 8181 }
        $rate = Request $account $port POST '/api/v1/account/login' @{email=$rateEmail;password='invalid-password-fixture'}
        if ($rate.StatusCode -ne 401) { throw 'Distributed rate fixture failed before limit.' }
    }
    if ((Request $account 8181 POST '/api/v1/account/login' @{email=$rateEmail;password='invalid-password-fixture'}).StatusCode -ne 429) { throw 'Distributed attempt counter failed.' }
    Stop-Node $a
    Stop-Node $b
    $resumed = Start-Node 8180
    $restored = (Request $account 8180 GET '/api/v1/account/profile').Content | ConvertFrom-Json
    if ($restored.id -ne $identity.id) { throw 'Account session lost after native process restart.' }
    $messages = (Request $account 8180 GET "/api/v1/pdf/ai/conversations/$($chat.id)/messages").Content | ConvertFrom-Json
    if ($messages.Count -ne 2 -or $messages[0].text -ne 'Native persistence probe') { throw 'Messages lost after restart.' }
    $response = Request $account 8180 GET "/api/v1/pdf/ai/documents/$document/file"
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($response.RawContentStream.ToArray()))
    if ($hash -ne (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash) { throw 'Persisted file differs from source.' }
    $restoredGuest = (Request $guest 8180 GET '/api/v1/account/session').Content | ConvertFrom-Json
    if ($restoredGuest.workspaceId -ne $guestSpace.workspaceId) { throw 'Guest session lost after restart.' }
    Write-Output 'PASS session, guest workspace, messages and PDF survive native process termination/restart'
    if ((Request $account 8180 POST '/api/v1/account/login' @{email=$rateEmail;password='invalid-password-fixture'}).StatusCode -ne 429) { throw 'Rate limit lost after restart.' }
    Write-Output 'PASS distributed Redis attempt counter persists across native restart'
    $unavailable = Start-Node 8181 9
    $closed = Request $account 8181 GET '/api/v1/account/session'
    if ($closed.StatusCode -ne 503 -or ($closed.Content | ConvertFrom-Json).code -ne 'SESSION_STORAGE_UNAVAILABLE') {
        throw 'Unavailable session storage did not fail closed.'
    }
    $core = Invoke-WebRequest 'http://127.0.0.1:8181/api/v1/pdf/info' -Method Post -WebSession $account -Form @{file=Get-Item -LiteralPath $fixture} -TimeoutSec 15
    if ($core.StatusCode -ne 200 -or ($core.Content | ConvertFrom-Json).pageCount -ne 1) {
        throw 'Stateless PDF operation depended on session storage.'
    }
    Write-Output 'PASS unavailable Redis/Elasticsearch leave PDF operations available with an existing cookie; stateful routes fail closed'
    Stop-Node $unavailable
    Hold-Model $true
    $crashId=[Guid]::NewGuid().ToString()
    $null=Request $account 8180 POST "/api/v1/pdf/ai/conversations/$($chat.id)/messages" @{requestId=$crashId;mode='ASK';question='Crash recovery probe'}
    $partial=Await-Message $account 8180 $crashId -Partial
    Stop-Node $worker
    Hold-Model $false
    $worker=Start-Node 0 -Worker
    $recovered=Await-Message $account 8180 $crashId
    if($recovered.status -ne 'COMPLETED' -or $recovered.sequence -le $partial.sequence -or $recovered.text -ne 'Alpha is 42. [p1-0]'){throw 'Worker lease recovery did not replace the partial attempt.'}
    $messages=(Request $account 8180 GET "/api/v1/pdf/ai/conversations/$($chat.id)/messages").Content|ConvertFrom-Json
    if($messages.Count -ne 4){throw 'Worker recovery duplicated a business message.'}
    $record=(& docker compose --env-file (Join-Path $PSScriptRoot '.env.local') -f (Join-Path $PSScriptRoot 'compose.yml') exec -T postgres psql -U pdf_workbench -d pdf_workbench -At -c "SELECT json_build_object('attempt',attempt,'fence',fence,'state',state) FROM $schema.work_tasks WHERE target_id='$crashId'::uuid AND kind='GENERATE';")|ConvertFrom-Json
    if($record.attempt -lt 2 -or $record.fence -lt 2 -or $record.state -ne 'SUCCEEDED'){throw 'Persistent recovery token did not advance.'}
    Write-Output 'PASS forced Worker termination recovers with a new fencing token and no duplicated messages'
    $remote=Start-Node 8181
    Hold-Model $true
    $cancelId=[Guid]::NewGuid().ToString()
    $null=Request $account 8180 POST "/api/v1/pdf/ai/conversations/$($chat.id)/messages" @{requestId=$cancelId;mode='ASK';question='Remote cancellation probe'}
    $null=Await-Message $account 8180 $cancelId -Partial
    $cancelled=(Request $account 8181 POST "/api/v1/pdf/ai/runs/$cancelId/cancel").Content|ConvertFrom-Json
    if($cancelled.status -ne 'STOPPED'){throw 'Cross-Web cancellation failed.'}
    $events=Request $account 8180 GET "/api/v1/pdf/ai/runs/$cancelId/events"
    if($events.Content -notmatch 'STOPPED'){throw 'Cross-Web SSE did not observe the committed cancellation.'}
    $aborted=$false
    for($i=0;$i -lt 50;$i++) {
        $control=Invoke-RestMethod 'http://127.0.0.1:8189/control'
        if($control.activeChats -eq 0){$aborted=$true;break}
        Start-Sleep -Milliseconds 100
    }
    if(!$aborted){throw 'Worker did not abort the cancelled model stream.'}
    Hold-Model $false
    Start-Sleep -Milliseconds 500
    $stable=(Request $account 8180 GET "/api/v1/pdf/ai/runs/$cancelId").Content|ConvertFrom-Json
    if($stable.status -ne 'STOPPED' -or $stable.sequence -ne $cancelled.sequence){throw 'A stale worker overwrote cancellation.'}
    $followId=[Guid]::NewGuid().ToString()
    $null=Request $account 8180 POST "/api/v1/pdf/ai/conversations/$($chat.id)/messages" @{requestId=$followId;mode='ASK';question='Consumer remains available'}
    if((Await-Message $account 8181 $followId).status -ne 'COMPLETED'){throw 'Cancellation stopped a shared queue consumer.'}
    $queues=(& docker compose --env-file (Join-Path $PSScriptRoot '.env.local') -f (Join-Path $PSScriptRoot 'compose.yml') exec -T rabbitmq rabbitmqctl list_queues -p workbench --formatter json name consumers)|ConvertFrom-Json
    $readingQueue=$queues|Where-Object{$_.name -eq "pdf-workbench.test.$run.reading"}
    if($readingQueue.consumers -ne 2){throw 'Reading consumer count fell after cancellation.'}
    Write-Output 'PASS another Web instance cancels the Worker, closes the provider stream and exposes the same SSE snapshot; consumers remain available'
    $removed=Request $account 8181 DELETE "/api/v1/pdf/ai/conversations/$($chat.id)" @{deleteDocument=$true}
    if($removed.StatusCode -ne 200){throw 'Cleanup submission failed.'}
    $gone=$false
    for($i=0;$i -lt 150;$i++) {
        if((Request $account 8180 GET "/api/v1/pdf/ai/documents/$document").StatusCode -eq 404){$gone=$true;break}
        Start-Sleep -Milliseconds 100
    }
    if(!$gone -or (Test-Path -LiteralPath (Join-Path $directory "files/users/$($identity.id)/$document.pdf"))){throw 'Asynchronous cleanup did not remove the tombstoned source.'}
    Write-Output 'PASS RabbitMQ cleanup removes the tombstoned document and source after conversation deletion'

} finally {
    foreach ($node in $processes) { Stop-Node $node; $node.Process.Dispose() }
    try {
        $previousRabbit = $env:RABBITMQ_PASSWORD
        try {
            $env:RABBITMQ_PASSWORD = $credentials.RABBITMQ_PASSWORD
            & $java ("-Dloader.path=" + (Join-Path $root 'backend/target/test-classes')) '-Dloader.main=cn.edu.pdfworkbench.TaskNativeFixture' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher cleanup "pdf-workbench.test.$run"
            if ($LASTEXITCODE -ne 0) { throw 'Verification queue cleanup failed.' }
        } finally { $env:RABBITMQ_PASSWORD = $previousRabbit }
        $searchHeaders = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('elastic:' + $credentials.ELASTIC_PASSWORD)) }
        $removed = Invoke-WebRequest "http://127.0.0.1:19200/pdf-workbench-test-$run-v1" -Method Delete -Headers $searchHeaders -SkipHttpErrorCheck -TimeoutSec 10
        if ($removed.StatusCode -notin @(200,404)) { throw 'Verification index cleanup failed.' }
    } finally {
        if ($schema -notmatch '^wb_native_[a-f0-9]{32}$') { throw 'Invalid verification schema.' }
        & docker compose --env-file (Join-Path $PSScriptRoot '.env.local') -f (Join-Path $PSScriptRoot 'compose.yml') exec -T postgres psql -U pdf_workbench -d pdf_workbench -v ON_ERROR_STOP=1 -c "DROP SCHEMA IF EXISTS $schema CASCADE;" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Verification schema cleanup failed.' }
    }
}
