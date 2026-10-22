#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$source = $PSScriptRoot
$root = [IO.Path]::GetFullPath((Join-Path $source ('../backend/target/lifecycle probe ' + [Guid]::NewGuid().ToString('N'))))
$deploy = Join-Path $root 'deploy'
$runRoot = Join-Path $deploy 'runtime/lifecycle'
New-Item -ItemType Directory -Path $runRoot,(Join-Path $root 'backend/target'),(Join-Path $root 'classes') -Force | Out-Null
foreach ($name in @('ApplicationProcess.ps1','LocalLifecycle.ps1','Supervise-Local.ps1')) {
    Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $deploy $name)
}
[IO.File]::WriteAllText((Join-Path $deploy '.env.local'), ((@('POSTGRES_PASSWORD','REDIS_PASSWORD','ELASTIC_PASSWORD','RABBITMQ_PASSWORD','S3_ACCESS_KEY','S3_SECRET_KEY') | ForEach-Object { "$_=test-only" }) -join "`n"))
$fixture = @"
import java.nio.file.*;
import java.util.*;
public class LifecycleFixture {
  public static void main(String[] args) throws Exception {
    var values = new HashMap<String,String>();
    for (String arg : args) { var pair=arg.split("=",2); if(pair.length==2)values.put(pair[0],pair[1]); }
    String role=values.get("--tasks.role");
    if (role.equals(System.getenv("LIFECYCLE_FIXTURE_FAIL_ROLE"))) System.exit(7);
    Path ready=Path.of(values.get("--deployment.ready-file"));
    Files.writeString(ready, Long.toString(ProcessHandle.current().pid()), StandardOpenOption.CREATE_NEW);
    int value; do { value=System.in.read(); } while(value!=-1 && value!=4);
    if (role.equals(System.getenv("LIFECYCLE_FIXTURE_IGNORE_STOP"))) Thread.sleep(120000);
    Files.deleteIfExists(ready);
    Files.writeString(ready.resolveSibling(role+".closed"),"normal");
  }
}
"@
[IO.File]::WriteAllText((Join-Path $root 'LifecycleFixture.java'),$fixture)
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') -d (Join-Path $root 'classes') (Join-Path $root 'LifecycleFixture.java')
if ($LASTEXITCODE -ne 0) { throw 'Fixture compile failed.' }
& (Join-Path $env:JAVA_HOME 'bin/jar.exe') --create --file (Join-Path $root 'backend/target/pdf-workbench-1.1.0.jar') --main-class LifecycleFixture -C (Join-Path $root 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'Fixture packaging failed.' }
. (Join-Path $deploy 'LocalLifecycle.ps1')
. (Join-Path $deploy 'ApplicationProcess.ps1')
function Assert([bool] $Condition,[string] $Message) { if (!$Condition) { throw $Message } }
function Wait-State([string] $Expected) {
    $end=[DateTime]::UtcNow.AddSeconds(20)
    do {
        $state=Get-DeploymentState
        if ($state -and $state.status -eq $Expected -and (Test-DeploymentOwner)) { return $state }
        Start-Sleep -Milliseconds 100
    } while([DateTime]::UtcNow -lt $end)
    throw "Expected state $Expected not reached. Logs: $runRoot"
}
function Start-FixtureSupervisor {
    $run=[Guid]::NewGuid().ToString('N')
    $script:ExpectedRun=$run
    $info=[Diagnostics.ProcessStartInfo]::new((Join-Path $PSHOME 'pwsh.exe'))
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    foreach($arg in @('-NoProfile','-File',(Join-Path $deploy 'Supervise-Local.ps1'),'-Run',$run)) { $info.ArgumentList.Add($arg) }
    return [Diagnostics.Process]::Start($info)
}
$parentPassword=[Environment]::GetEnvironmentVariable('DATABASE_PASSWORD')
$info=New-WorkbenchStartInfo -Role worker -DatabaseSchema 'test_schema' -ReadyFile (Join-Path $runRoot 'space in name.ready')
Assert ($info.Environment['DATABASE_PASSWORD'] -ceq 'test-only') 'Child credential missing.'
Assert ([Environment]::GetEnvironmentVariable('DATABASE_PASSWORD') -ceq $parentPassword) 'Parent environment changed.'
Assert ($info.ArgumentList.Contains('--spring.main.web-application-type=none')) 'Worker is not headless.'
Assert ($info.ArgumentList.Contains('--deployment.ready-file='+(Join-Path $runRoot 'space in name.ready'))) 'Path was split into arguments.'
Assert (!(($info.ArgumentList -join ' ').Contains('test-only'))) 'Credential leaked into arguments.'
$failed=$false
try { $null=New-WorkbenchStartInfo -DatabaseSchema '../invalid' } catch { $failed=$true }
Assert $failed 'Invalid schema accepted.'
Write-Output 'PASS child environment isolation, headless role, argument boundaries and invalid schema'
$supervisor=$null
try {
    $supervisor=Start-FixtureSupervisor
    $state=Wait-State 'running'
    Assert (Test-DeploymentOwner) 'Supervisor does not own lease.'
    $duplicate=Start-FixtureSupervisor
    try { Assert ($duplicate.WaitForExit(10000)) 'Duplicate supervisor did not reject the active lease.'; Assert ($duplicate.ExitCode -ne 0) 'Duplicate supervisor accepted active lease.' }
    finally { $duplicate.Dispose() }
    Assert ((Get-DeploymentState).run -eq $state.run) 'Duplicate supervisor changed state.'
    Request-DeploymentStop $state
    Assert ($supervisor.WaitForExit(15000)) 'Normal shutdown did not finish.'
    Assert ($supervisor.ExitCode -eq 0) 'Normal shutdown failed.'
    Assert (!(Test-DeploymentOwner)) 'Ownership survived process exit.'
    foreach($role in @('web','worker')) { Assert (Test-Path -LiteralPath (Join-Path $runRoot ($state.run+"/$role.closed"))) 'Child did not stop normally.' }
    Write-Output 'PASS ready handshake, duplicate rejection, graceful stop and ownership release'
    $supervisor.Dispose(); $supervisor=Start-FixtureSupervisor
    do { Start-Sleep -Milliseconds 100; $state=Wait-State 'running' } while($state.run -ne $ExpectedRun)
    Assert (!(Test-Path -LiteralPath (Join-Path $runRoot ($state.run+'/stop')))) 'Stale stop crossed run boundary.'
    $supervisor.Kill(); $supervisor.WaitForExit()
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do {
        $closed=@('web','worker' | Where-Object { Test-Path -LiteralPath (Join-Path $runRoot ($state.run+"/$_.closed")) }).Count
        if($closed -eq 2){break}; Start-Sleep -Milliseconds 100
    } while([DateTime]::UtcNow -lt $deadline)
    Assert ($closed -eq 2) 'Supervisor loss did not close child stdin.'
    Assert (!(Test-DeploymentOwner)) 'Stale state was considered active.'
    Write-Output 'PASS restart run isolation and supervisor-loss EOF cleanup'
    $supervisor.Dispose()
    $prior=$env:LIFECYCLE_FIXTURE_FAIL_ROLE
    try { $env:LIFECYCLE_FIXTURE_FAIL_ROLE='web'; $supervisor=Start-FixtureSupervisor }
    finally { $env:LIFECYCLE_FIXTURE_FAIL_ROLE=$prior }
    Assert ($supervisor.WaitForExit(20000)) 'Failed startup did not roll back.'
    Assert ($supervisor.ExitCode -ne 0) 'Failed child was reported successful.'
    $state=Get-DeploymentState
    Assert ($state.status -eq 'failed') 'Failure state missing.'
    Assert (Test-Path -LiteralPath (Join-Path $runRoot ($state.run+'/worker.closed'))) 'Healthy sibling was not closed.'
    Write-Output 'PASS failed child startup rolls back its sibling'
    $supervisor.Dispose()
    $prior=$env:LIFECYCLE_FIXTURE_IGNORE_STOP
    try { $env:LIFECYCLE_FIXTURE_IGNORE_STOP='web'; $supervisor=Start-FixtureSupervisor }
    finally { $env:LIFECYCLE_FIXTURE_IGNORE_STOP=$prior }
    $state=Wait-State 'running'
    Request-DeploymentStop $state
    Assert ($supervisor.WaitForExit(45000)) 'Unresponsive child exceeded shutdown budget.'
    Assert ($supervisor.ExitCode -ne 0) 'Forced stop was reported as graceful.'
    $state=Get-DeploymentState
    Assert ($state.forced.Count -eq 1 -and $state.forced[0] -eq 'web') 'Forced termination was not recorded.'
    Assert (Test-Path -LiteralPath (Join-Path $runRoot ($state.run+'/worker.closed'))) 'Worker was not closed after forced web termination.'
    Write-Output 'PASS bounded forced termination and failed shutdown status'
    $supervisor.Dispose(); $supervisor=Start-FixtureSupervisor
    $state=Wait-State 'running'
    $locked=[IO.File]::Open((Join-Path $runRoot 'state.json'),'Open','Read','None')
    try {
        Request-DeploymentStop $state
        Assert ($supervisor.WaitForExit(15000)) 'State write failure prevented child cleanup.'
        Assert ($supervisor.ExitCode -ne 0) 'State write failure was not reported.'
    } finally { $locked.Dispose() }
    foreach($role in @('web','worker')) { Assert (Test-Path -LiteralPath (Join-Path $runRoot ($state.run+"/$role.closed"))) 'State failure prevented graceful child shutdown.' }
    Assert (!(Test-DeploymentOwner)) 'State write failure retained the ownership lease.'
    Write-Output 'PASS state-write failure still closes children and releases ownership'
} finally {
    if($supervisor) {
        if(!$supervisor.HasExited) { $state=Get-DeploymentState; if($state){Request-DeploymentStop $state}; if(!$supervisor.WaitForExit(15000)){$supervisor.Kill();$supervisor.WaitForExit()} }
        $supervisor.Dispose()
    }
}
