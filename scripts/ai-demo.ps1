param(
    [string]$RabbitUser = $(if ($env:RABBITMQ_USER) { $env:RABBITMQ_USER } else { 'shift' }),
    [string]$RabbitPassword = $(if ($env:RABBITMQ_PASSWORD) { $env:RABBITMQ_PASSWORD } else { 'shift_dev' }),
    [int]$TimeoutSeconds = 150
)

$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:15672/api'
$vhost = 'shift-happens'
$exchange = 'shift-happens.events'
$queue = 'ai-demo-' + [guid]::NewGuid().ToString('N')
$auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${RabbitUser}:${RabbitPassword}"))
$headers = @{ Authorization = "Basic $auth" }

function Invoke-RabbitApi {
    param([string]$Method, [string]$Path, $Body)
    $args = @{ Method = $Method; Uri = "$base$Path"; Headers = $headers }
    if ($null -ne $Body) {
        $args.ContentType = 'application/json'
        $args.Body = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
    }
    Invoke-RestMethod @args
}

try {
    # Bind a private, temporary queue before publishing, so the output cannot be missed.
    Invoke-RabbitApi 'Put' "/queues/$vhost/$queue" @{
        durable = $false; auto_delete = $true; arguments = @{}
    } | Out-Null
    Invoke-RabbitApi 'Post' "/bindings/$vhost/e/$exchange/q/$queue" @{
        routing_key = 'ai.replacement.suggested.v1'; arguments = @{}
    } | Out-Null

    $eventId = [guid]::NewGuid().ToString()
    $event = @{
        eventId = $eventId
        eventType = 'scheduling.shift.understaffed.v1'
        occurredAt = [DateTime]::UtcNow.ToString('o')
        producer = 'scheduling-service'
        aggregateId = 42
        aggregateVersion = 1
        correlationId = [guid]::NewGuid().ToString()
        causationId = $null
        data = @{
            shiftId = 42
            shiftName = 'Demo evening shift'
            departmentId = 1
            startDatetime = [DateTime]::UtcNow.AddDays(2).ToString('o')
            endDatetime = [DateTime]::UtcNow.AddDays(2).AddHours(8).ToString('o')
            jobRoleId = 3
            missingCount = 1
            candidates = @(
                @{ employeeId = 7; name = 'Demo Anna'; hoursThisWeek = 28; weeklyContractHours = 37; proficiencyLevel = 'experienced' },
                @{ employeeId = 8; name = 'Demo Bo'; hoursThisWeek = 12; weeklyContractHours = 37; proficiencyLevel = 'trained' },
                @{ employeeId = 9; name = 'Demo Clara'; hoursThisWeek = 20; weeklyContractHours = 30; proficiencyLevel = 'experienced' }
            )
        }
    }
    $payload = ConvertTo-Json -InputObject $event -Depth 20 -Compress
    $published = Invoke-RabbitApi 'Post' "/exchanges/$vhost/$exchange/publish" @{
        properties = @{}
        routing_key = 'scheduling.shift.understaffed.v1'
        payload = $payload
        payload_encoding = 'string'
    }
    if (-not $published.routed) { throw 'Event was not routed to ai.suggestions. Check ai-service and RabbitMQ.' }
    Write-Host "Sent demo event $eventId. Waiting for AI suggestion..."

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $messages = @(Invoke-RabbitApi 'Post' "/queues/$vhost/$queue/get" @{
            count = 1; ackmode = 'ack_requeue_false'; encoding = 'auto'; truncate = 50000
        })
        foreach ($message in $messages) {
            if (-not $message.payload) { continue }
            $result = $message.payload | ConvertFrom-Json
            if ($result.causationId -ne $eventId) { continue }
            Write-Host "Source: $($result.data.source)"
            Write-Host "Model: $($result.data.model)"
            Write-Host "Reason: $($result.data.reason)"
            Write-Host 'Ranking:'
            $result.data.rankedCandidates | Sort-Object rank | Format-Table rank, employeeId -AutoSize
            Write-Host 'Complete output event:'
            $result | ConvertTo-Json -Depth 20
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "No suggestion received within $TimeoutSeconds seconds. Check: docker compose -f docker-compose.microservices.yml logs ai-service"
} finally {
    try { Invoke-RabbitApi 'Delete' "/queues/$vhost/$queue" $null | Out-Null } catch { }
}
