param(
    [string]$Deployment = 'frontend',
    [string]$Namespace = 'shift-happens',
    [string]$GrafanaUrl = 'http://localhost:3001',
    [string]$GrafanaUser = 'admin',
    [string]$GrafanaPassword = 'admin',
    [int]$WaitSeconds = 120,
    [int]$PauseSeconds = 0,
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

$before = Get-DeploymentState
if ($before.Desired -lt 1) {
    throw "Deployment $Deployment has no desired replicas."
}

Write-Host ("Rolling deployment/{0}: desired={1}, ready={2}, updated={3}" -f `
    $Deployment, $before.Desired, $before.Ready, $before.Updated)
Add-RolloutAnnotation ("Rolling update started: {0}/{1}" -f $Namespace, $Deployment)

# This creates a new ReplicaSet in the same way a new image tag would. The running
# Deployment keeps serving from the old pods until the new ones are Ready.
kubectl -n $Namespace rollout restart "deployment/$Deployment"

# A durable rolling update can finish between two 15-second kube-state-metrics
# scrapes. Pausing for the demo leaves one new pod visible while the old pods
# keep serving; resume below completes it normally.
if ($PauseSeconds -gt 0) {
    Start-Sleep -Seconds 2
    kubectl -n $Namespace rollout pause "deployment/$Deployment"
    Add-RolloutAnnotation ("Rolling update paused for inspection: {0}/{1}" -f $Namespace, $Deployment)
    Write-Host "Rollout paused for $PauseSeconds seconds (old pods keep serving)..."
    Start-Sleep -Seconds $PauseSeconds
    kubectl -n $Namespace rollout resume "deployment/$Deployment"
    Add-RolloutAnnotation ("Rolling update resumed: {0}/{1}" -f $Namespace, $Deployment)
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    Start-Sleep -Seconds 2
    $state = Get-DeploymentState
    Write-Host ("{0:HH:mm:ss} desired={1} ready={2} updated={3} unavailable={4}" -f `
        (Get-Date), $state.Desired, $state.Ready, $state.Updated, $state.Unavailable)
    $done = $state.Ready -eq $state.Desired -and $state.Updated -eq $state.Desired
} while (-not $done -and (Get-Date) -lt $deadline)

if (-not $done) {
    throw "Rollout did not finish within $WaitSeconds seconds."
}

kubectl -n $Namespace rollout status "deployment/$Deployment" --timeout="$($WaitSeconds)s"
Add-RolloutAnnotation ("Rolling update complete: {0}/{1}" -f $Namespace, $Deployment)

# Leave a small tail so the next Prometheus scrape and Grafana refresh see the final state.
if ($SettleSeconds -gt 0) {
    Write-Host "Settling for $SettleSeconds seconds so Grafana can refresh..."
    Start-Sleep -Seconds $SettleSeconds
}

Write-Host ''
Write-Host 'Open the rollout dashboard:'
Write-Host '  http://localhost:3001/d/shift-happens-kubernetes-rollout?from=now-15m&to=now'
