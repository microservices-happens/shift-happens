param(
    [int]$Count = 10000
)

$ErrorActionPreference = 'Stop'
$encoded = kubectl -n shift-happens get secret rabbitmq-secrets -o jsonpath='{.data.RABBITMQ_DEFAULT_PASS}'
if (-not $encoded) { throw 'rabbitmq-secrets not found in namespace shift-happens' }
$env:RABBITMQ_PASSWORD = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
$env:RABBITMQ_HOST = '127.0.0.1'
$env:RABBITMQ_PORT = '5672'
$env:RABBITMQ_VHOST = 'shift-happens'
$env:RABBITMQ_USER = 'shift'

$forward = $null
$alreadyListening = Test-NetConnection -ComputerName 127.0.0.1 -Port 5672 -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $alreadyListening) {
    Write-Host 'Starting kubectl port-forward for RabbitMQ AMQP...'
    $forward = Start-Process kubectl -ArgumentList @(
        '-n', 'shift-happens', 'port-forward', '--address', '127.0.0.1', 'svc/rabbitmq', '5672:5672'
    ) -PassThru -WindowStyle Hidden
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-NetConnection -ComputerName 127.0.0.1 -Port 5672 -InformationLevel Quiet -WarningAction SilentlyContinue) { break }
    }
}

try {
    python scripts/notification-burst.py $Count
} finally {
    if ($forward -and -not $forward.HasExited) { Stop-Process -Id $forward.Id -Force }
}

