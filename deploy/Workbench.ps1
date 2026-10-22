#requires -Version 7.2
param([ValidateSet('start','stop','restart','status','help')][string] $Action = 'help')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
$root = Split-Path -Parent $PSScriptRoot
$compose = @('compose','--env-file',(Join-Path $PSScriptRoot '.env.local'),'-f',(Join-Path $PSScriptRoot 'compose.yml'))
function Test-ApplicationPortAvailable {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,8080)
    try { $listener.Start(); return $true } catch { return $false }
    finally { $listener.Stop() }
}
function Show-Status {
    $state = Get-DeploymentState
    $owned = Test-DeploymentOwner
    $status = if ($owned -and $state) { $state.status } elseif (!(Test-ApplicationPortAvailable)) { 'unmanaged listener on port 8080' } else { 'stopped' }
    Write-Output "Applications: $status"
    if ($state) {
        Write-Output "Last run: $($state.run) ($($state.status))"
        if ($state.failure) { Write-Output $state.failure }
    }
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.env.local')) {
        $components = & docker @compose ps --all --format json
        if ($LASTEXITCODE -ne 0) { throw 'Middleware status is unavailable.' }
        $components | ConvertFrom-Json | Select-Object Service,State,Health | Format-Table -AutoSize
    }
}
function Stop-Deployment {
    if (!(Test-DeploymentOwner) -and !(Test-ApplicationPortAvailable)) {
        throw 'Port 8080 belongs to an unmanaged process.'
    }
    if (Test-DeploymentOwner) {
        $state = Get-DeploymentState
        if (!$state) { throw 'Supervisor state is unavailable.' }
        Request-DeploymentStop $state
        Wait-DeploymentStopped
        $state = Get-DeploymentState
        if ($state.status -eq 'failed') { throw 'Application shutdown requires inspection of the run logs; middleware remains running.' }
    }
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.env.local')) {
        & docker @compose stop --timeout 30
        if ($LASTEXITCODE -ne 0) { throw 'Middleware stop failed.' }
    }
    Write-Output 'Deployment stopped. Data volumes and credentials retained.'
}
function Start-Deployment {
    if (Test-DeploymentOwner) {
        $state = Get-DeploymentState
        if ($state.status -eq 'running') { Write-Output 'Deployment already running.'; return }
        throw 'Another application lifecycle is active.'
    }
    foreach ($file in @('backend/target/pdf-workbench-1.1.0.jar','frontend/dist/index.html')) {
        if (!(Test-Path -LiteralPath (Join-Path $root $file))) { throw "Missing build artifact: $file" }
    }
    if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw 'JAVA_HOME must point to JDK 21 or later.' }
    $version = (& (Join-Path $env:JAVA_HOME 'bin/java.exe') -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "([0-9]+)' -or [int]$Matches[1] -lt 21) { throw 'JDK 21 or later is required.' }
    if (!(Test-ApplicationPortAvailable)) { throw 'Port 8080 is occupied by an unmanaged process.' }
    & (Join-Path $PSScriptRoot 'Start-Local.ps1')
    $run = [Guid]::NewGuid().ToString('N')
    $directory = Join-Path $LifecycleDirectory $run
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $supervisor = Join-Path $PSScriptRoot 'Supervise-Local.ps1'
    $process = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -WindowStyle Hidden -PassThru -WorkingDirectory $root `
        -ArgumentList @('-NoLogo','-NoProfile','-File',('"'+$supervisor+'"'),'-Run',$run)
    try {
        $end = [DateTime]::UtcNow.AddSeconds(200)
        while ([DateTime]::UtcNow -lt $end) {
            if ($process.HasExited) { throw "Application startup failed; logs: $directory" }
            $state = Get-DeploymentState
            if ($state -and $state.run -eq $run -and $state.status -eq 'running') {
                $response = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/v3/api-docs' -TimeoutSec 10
                if ($response.StatusCode -ne 200 -or !($response.Content | ConvertFrom-Json).openapi) { throw 'Gateway API readiness failed.' }
                Write-Output 'Deployment ready: http://127.0.0.1:5173'
                Write-Output "Run logs: $directory"
                return
            }
            Start-Sleep -Milliseconds 300
        }
        throw "Application startup timed out; logs: $directory"
    } catch {
        if (!$process.HasExited) {
            [IO.File]::WriteAllText((Join-Path $directory 'stop'),'')
            if (!$process.WaitForExit(90000)) { throw 'Supervisor shutdown timed out; inspect lifecycle logs.' }
        }
        throw
    } finally { $process.Dispose() }
}
try {
    if ($Action -eq 'help') {
        Write-Output 'workbench.cmd start | status | stop | restart'
        Write-Output 'Native Windows Web/Worker; Docker Compose middleware. Stop retains persistent data.'
        exit 0
    }
    if ($Action -eq 'status') { Show-Status; exit 0 }
    New-Item -ItemType Directory -Path $LifecycleDirectory -Force | Out-Null
    try { $operation = [IO.File]::Open((Join-Path $LifecycleDirectory 'operation.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { throw 'Another deployment command is in progress.' }
    try {
        switch ($Action) {
            'start' { Start-Deployment }
            'stop' { Stop-Deployment }
            'restart' { Stop-Deployment; Start-Deployment }
        }
    } finally { $operation.Dispose() }
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 1 }
