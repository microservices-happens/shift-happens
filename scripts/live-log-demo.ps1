param(
    [string]$Gateway = 'https://localhost:30443',
    [int]$Count = 10,
    [int]$DelayMs = 750,
    [string]$AdminEmail = 'admin@shift-happens.example',
    [string]$AdminPassword = 'Demo1234!'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$base = $Gateway.TrimEnd('/')
$run = Get-Date -Format 'HHmmss'

function Show-RequestResult {
    param([string]$Label, [string]$CorrelationId, $Response)
    Write-Host ("{0,-18} {1}  correlation-id={2}" -f $Label, $Response.StatusCode, $CorrelationId)
}

for ($i = 1; $i -le $Count; $i++) {
    $correlationId = "log-demo-$run-$i"

    # A real login produces a 200 in identity-service and a JWT for the workforce read.
    $login = Invoke-WebRequest -SkipCertificateCheck -SkipHttpErrorCheck -Method Post `
        -Uri "$base/api/v1/auth/login" `
        -Headers @{ 'X-Correlation-Id' = $correlationId } `
        -ContentType 'application/json' `
        -Body (@{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json) `
        -TimeoutSec 15
    Show-RequestResult 'identity login' $correlationId $login

    $token = $null
    if ($login.StatusCode -eq 200) {
        $token = ($login.Content | ConvertFrom-Json).token
    }
    $headers = @{ 'X-Correlation-Id' = $correlationId }
    if ($token) { $headers.Authorization = "Bearer $token" }

    $employees = Invoke-WebRequest -SkipCertificateCheck -SkipHttpErrorCheck -Method Get `
        -Uri "$base/api/v1/employees" `
        -Headers $headers `
        -TimeoutSec 15
    Show-RequestResult 'workforce employees' $correlationId $employees

    if ($i -lt $Count -and $DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }
}

Write-Host ''
Write-Host 'Open the live logs panel:'
Write-Host '  http://localhost:3001/d/shift-happens-kubernetes-rollout'
Write-Host 'Filter by the correlation-id prefixes above if the panel is busy.'
