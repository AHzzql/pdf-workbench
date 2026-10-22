#requires -Version 7.2
$script:LifecycleDirectory = Join-Path $PSScriptRoot 'runtime/lifecycle'
function Get-DeploymentState {
    $file = Join-Path $LifecycleDirectory 'state.json'
    if (!(Test-Path -LiteralPath $file)) { return $null }
    $stream = [IO.File]::Open($file, 'Open', 'Read', [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
    $reader = [IO.StreamReader]::new($stream)
    try { $state = $reader.ReadToEnd() | ConvertFrom-Json }
    finally { $reader.Dispose() }
    if ($state.run -cnotmatch '^[a-f0-9]{32}$') { throw 'Invalid deployment state.' }
    return $state
}
function Set-DeploymentState($State) {
    $file = Join-Path $LifecycleDirectory 'state.json'
    $temporary = "$file.$PID.tmp"
    [IO.File]::WriteAllText($temporary, ($State | ConvertTo-Json -Depth 5))
    [IO.File]::Move($temporary, $file, $true)
}
function Test-DeploymentOwner {
    $file = Join-Path $LifecycleDirectory 'owner.lock'
    if (!(Test-Path -LiteralPath $file)) { return $false }
    try {
        $lease = [IO.File]::Open($file, 'Open', 'ReadWrite', 'None')
        $lease.Dispose()
        return $false
    } catch [IO.IOException] { return $true }
}
function Request-DeploymentStop($State) {
    if ($State.run -cnotmatch '^[a-f0-9]{32}$') { throw 'Invalid deployment state.' }
    $path = Join-Path $LifecycleDirectory ($State.run + '/stop')
    [IO.File]::WriteAllText($path, '')
}
function Wait-DeploymentStopped([int] $TimeoutSeconds = 90) {
    $end = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (Test-DeploymentOwner) {
        if ([DateTime]::UtcNow -gt $end) { throw 'Application shutdown timed out; inspect deploy/runtime/lifecycle.' }
        Start-Sleep -Milliseconds 200
    }
}
