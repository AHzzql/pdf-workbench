#requires -Version 7.2
param([Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{32}$')][string] $Run)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ApplicationProcess.ps1')
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
$lease = [IO.File]::Open((Join-Path $LifecycleDirectory 'owner.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$runDirectory = Join-Path $LifecycleDirectory $Run
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$children = [Collections.Generic.List[object]]::new()
$state = [ordered]@{ run=$Run; supervisor=$PID; status='starting'; web=$null; worker=$null; failure=$null; forced=@() }
$stopFile = Join-Path $runDirectory 'stop'
function Start-Application([string] $Role) {
    $ready = Join-Path $runDirectory "$Role.ready"
    $info = New-WorkbenchStartInfo -Role $Role -ReadyFile $ready
    $outFile = [IO.File]::Open((Join-Path $runDirectory "$Role.log"), 'CreateNew', 'Write', 'Read')
    $errFile = [IO.File]::Open((Join-Path $runDirectory "$Role.error.log"), 'CreateNew', 'Write', 'Read')
    $process = $null
    try {
        $process = [Diagnostics.Process]::Start($info)
        $child = [pscustomobject]@{ Role=$Role; Process=$process; Ready=$ready; Output=$outFile; Error=$errFile;
            OutputTask=$process.StandardOutput.BaseStream.CopyToAsync($outFile);
            ErrorTask=$process.StandardError.BaseStream.CopyToAsync($errFile) }
        $children.Add($child)
        $state[$Role] = $process.Id
        Set-DeploymentState $state
    } catch {
        if ($process -and !$process.HasExited) { $process.Kill($true); $process.WaitForExit() }
        $outFile.Dispose(); $errFile.Dispose()
        throw
    }
}
function Assert-ChildrenAlive {
    foreach ($child in $children) {
        if ($child.Process.HasExited) { throw "$($child.Role) exited with code $($child.Process.ExitCode)." }
    }
}
function Test-ChildrenReady {
    foreach ($child in $children) {
        if (!(Test-Path -LiteralPath $child.Ready)) { return $false }
        if ([IO.File]::ReadAllText($child.Ready) -cne [string]$child.Process.Id) { return $false }
    }
    return $children.Count -eq 2
}
try {
    Set-DeploymentState $state
    Start-Application 'worker'
    Start-Application 'web'
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    while (!(Test-Path -LiteralPath $stopFile)) {
        Assert-ChildrenAlive
        if (Test-ChildrenReady) { $state.status='running'; Set-DeploymentState $state; break }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Application readiness timed out.' }
        Start-Sleep -Milliseconds 200
    }
    while (!(Test-Path -LiteralPath $stopFile)) {
        Assert-ChildrenAlive
        Start-Sleep -Milliseconds 300
    }
} catch {
    $state.failure = $_.Exception.Message
    [IO.File]::WriteAllText((Join-Path $runDirectory 'supervisor.error.log'), $state.failure)
} finally {
    $state.status='stopping'
    try { Set-DeploymentState $state } catch { $state.failure='Deployment state could not be written.' }
    # Stop accepting web requests before shutting down the worker.
    foreach ($role in @('web','worker')) {
        $child = $children | Where-Object Role -eq $role | Select-Object -First 1
        if (!$child) { continue }
        $process = $child.Process
        try {
            if (!$process.HasExited) {
                try { $process.StandardInput.Write([char]4); $process.StandardInput.Flush(); $process.StandardInput.Close() } catch {}
                if (!$process.WaitForExit(35000)) {
                    $state.forced += $role
                    $process.Kill($true)
                    if (!$process.WaitForExit(5000)) { throw "$role did not terminate." }
                }
            }
            $child.OutputTask.GetAwaiter().GetResult()
            $child.ErrorTask.GetAwaiter().GetResult()
        } catch { $state.failure = 'Application shutdown failed; inspect the run logs.' }
        finally { $child.Output.Dispose(); $child.Error.Dispose(); $process.Dispose() }
    }
    $state.status = if ($state.failure -or $state.forced.Count) { 'failed' } else { 'stopped' }
    try { Set-DeploymentState $state } finally { $lease.Dispose() }
}
if ($state.status -eq 'failed') { exit 1 }
