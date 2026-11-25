param(
    [string]$Deployment = 'identity-service',
    [string]$Namespace = 'shift-happens',
    [string]$GrafanaUrl = 'http://localhost:3001',
    [string]$GrafanaUser = 'admin',
    [string]$GrafanaPassword = 'admin',
    [string]$FromVersion = 'v1.0.0',
    [string]$ToVersion = 'v1.0.1',
    [int]$Replicas = 3,
    [int]$WaitSeconds = 300,
    [int]$PauseSeconds = 60,
    [int]$SettleSeconds = 20
)

$ErrorActionPreference = 'Stop'

function Get-DeploymentState {
    $deployment = kubectl -n $Namespace get deployment $Deployment -o json | ConvertFrom-Json
    [pscustomobject]@{
        Desired     = [int]$deployment.spec.replicas
        Ready       = [int]($deployment.status.readyReplicas)
        Updated     = [int]($deployment.status.updatedReplicas)
        Available   = [int]($deployment.status.availableReplicas)
        Unavailable = [int]($deployment.status.unavailableReplicas)
    }
}

function Set-VersionLabel {
    param([string]$Version)
    $patch = @{
        spec = @{
            template = @{
                metadata = @{
                    labels = @{ version = $Version }
                }
            }
        }
    } | ConvertTo-Json -Depth 10
    kubectl -n $Namespace patch "deployment/$Deployment" --type=merge -p $patch | Out-Null
}

function Add-RolloutAnnotation {
    param([string]$Text)
    try {
        $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${GrafanaUser}:${GrafanaPassword}"))
        $body = @{
            time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            tags = @('rolling-update')
            text = $Text
        } | ConvertTo-Json
        Invoke-RestMethod -Method Post -Uri "$($GrafanaUrl.TrimEnd('/'))/api/annotations" `
            -Headers @{ Authorization = "Basic $basic" } `
            -ContentType 'application/json' -Body $body | Out-Null
    } catch {
        Write-Warning "Could not create the Grafana annotation: $($_.Exception.Message)"
    }
}

Write-Host "Preparing $Replicas x $FromVersion pods for deployment/$Deployment..."
kubectl -n $Namespace scale "deployment/$Deployment" --replicas=$Replicas | Out-Null
Set-VersionLabel $FromVersion
kubectl -n $Namespace rollout status "deployment/$Deployment" --timeout="${WaitSeconds}s" | Out-Null
Add-RolloutAnnotation ("Baseline: {0} x {1}" -f $Replicas, $FromVersion)
kubectl -n $Namespace get pods -l "app=$Deployment" -L version --no-headers

Write-Host ''
Write-Host "Starting rolling update: $FromVersion -> $ToVersion (3 old + 1 new, then all new)..."
Set-VersionLabel $ToVersion

# Wait until the deployment has created the first new pod, then pause before
# the second replacement. The old ReplicaSet keeps serving during the pause.
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    $state = Get-DeploymentState
    if ($state.Updated -ge 1) { break }
}

if ($PauseSeconds -gt 0) {
    kubectl -n $Namespace rollout pause "deployment/$Deployment" | Out-Null
    Add-RolloutAnnotation ("Paused: {0} x {1} + 1 x {2} (old ReplicaSet still serving)" -f $Replicas, $FromVersion, $ToVersion)
    Write-Host "Rollout paused for $PauseSeconds seconds. This is the 3 old + 1 new phase."
    kubectl -n $Namespace get pods -l "app=$Deployment" -L version --no-headers
    Start-Sleep -Seconds $PauseSeconds
    kubectl -n $Namespace rollout resume "deployment/$Deployment" | Out-Null
    Add-RolloutAnnotation ("Resumed: replacing the remaining {0} x {1} pods" -f $Replicas, $FromVersion)
}

kubectl -n $Namespace rollout status "deployment/$Deployment" --timeout="${WaitSeconds}s"
Add-RolloutAnnotation ("Complete: {0} x {1}" -f $Replicas, $ToVersion)
kubectl -n $Namespace get pods -l "app=$Deployment" -L version --no-headers

# Let Prometheus scrape the final state and Grafana refresh the graph.
if ($SettleSeconds -gt 0) {
    Write-Host "Settling for $SettleSeconds seconds so Grafana can refresh..."
    Start-Sleep -Seconds $SettleSeconds
}

Write-Host ''
Write-Host 'Open the rollout dashboard:'
Write-Host '  http://localhost:3001/d/shift-happens-kubernetes-rollout?from=now-10m&to=now'
