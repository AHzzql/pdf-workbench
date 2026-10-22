#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
$entry = Join-Path $root 'workbench.cmd'
function Invoke-Deployment([string] $Action) {
    & $entry $Action
    if ($LASTEXITCODE -ne 0) { throw "Deployment command failed: $Action" }
}
function Assert([bool] $Condition, [string] $Message) { if (!$Condition) { throw $Message } }
function Configuration-Hashes {
    return @('.env.local','runtime/redis-users.acl','runtime/s3-identities.json','runtime/security.toml' | ForEach-Object {
        (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $_)).Hash
    })
}
Invoke-Deployment 'start'
$first = Get-DeploymentState
Assert ($first.status -eq 'running') 'Applications not ready.'
$hashes = Configuration-Hashes
$volumes = @(docker volume ls --filter label=com.docker.compose.project=pdf-workbench-local --format '{{.Name}}')
if ($LASTEXITCODE -ne 0 -or $volumes.Count -ne 5) { throw 'Persistent volumes unavailable.' }
$guest = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/api/v1/account/session' -SessionVariable browser -TimeoutSec 15
Assert ([bool]$guest.workspaceId) 'Guest session missing.'
Invoke-Deployment 'start'
Assert ((Get-DeploymentState).run -eq $first.run) 'Repeated start created duplicate processes.'
Invoke-Deployment 'status'
Invoke-Deployment 'restart'
$second = Get-DeploymentState
Assert ($second.run -ne $first.run -and $second.status -eq 'running') 'Restart did not produce a ready new run.'
$previous = Get-Content -Raw -LiteralPath (Join-Path $LifecycleDirectory ($first.run+'/web.log'))
Assert ($previous.Contains('Shutdown completed')) 'Web shutdown did not close application resources.'
$again = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/api/v1/account/session' -WebSession $browser -TimeoutSec 15
Assert ($again.workspaceId -eq $guest.workspaceId) 'Guest session did not survive restart.'
$oldWeb = Get-Process -Id $second.web
$oldWorker = Get-Process -Id $second.worker
Invoke-Deployment 'stop'
$stopped = Get-DeploymentState
Assert ($stopped.status -eq 'stopped' -and $stopped.forced.Count -eq 0) 'Shutdown was not graceful.'
Assert ($oldWeb.HasExited -and $oldWorker.HasExited) 'Managed applications survived stop.'
Assert (!(Test-DeploymentOwner)) 'Supervisor lease survived stop.'
$live = @(docker compose --env-file (Join-Path $PSScriptRoot '.env.local') -f (Join-Path $PSScriptRoot 'compose.yml') ps --status running --quiet)
Assert ($LASTEXITCODE -eq 0 -and $live.Count -eq 0) 'Middleware still running after stop.'
Invoke-Deployment 'stop'
Assert (!(Compare-Object $hashes (Configuration-Hashes))) 'Credentials changed across lifecycle.'
$afterVolumes = @(docker volume ls --filter label=com.docker.compose.project=pdf-workbench-local --format '{{.Name}}')
Assert ($LASTEXITCODE -eq 0 -and !(Compare-Object $volumes $afterVolumes)) 'Persistent volumes changed.'
Invoke-Deployment 'start'
$final = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/api/v1/account/session' -WebSession $browser -TimeoutSec 15
Assert ($final.workspaceId -eq $guest.workspaceId) 'Guest session lost after full stop/start.'
Write-Output 'PASS native application and middleware lifecycle, repeat start/stop, graceful restart, persistent volumes, stable credentials and Redis session continuity'
