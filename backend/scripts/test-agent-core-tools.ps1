param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Sid = "",
    [string]$StartIntersection = "",
    [string]$EndIntersection = "",
    [string]$VehicleId = "",
    [switch]$SkipAgentChat
)

$ErrorActionPreference = "Stop"

function New-TestResult {
    param(
        [string]$Name,
        [string]$Type,
        [string]$Status,
        [object]$Data = $null,
        [string]$ErrorMessage = ""
    )
    [ordered]@{
        name = $Name
        type = $Type
        status = $Status
        error = $ErrorMessage
        data = $Data
        timestamp = (Get-Date).ToString("o")
    }
}

function Invoke-JsonGet {
    param([string]$Path)
    Invoke-RestMethod -Uri "$BaseUrl$Path" -Method Get -TimeoutSec 30
}

function Invoke-AgentChat {
    param([string]$Message)
    $body = @{
        message = $Message
        sessionId = "agent-core-tool-test"
        sid = $(if ([string]::IsNullOrWhiteSpace($Sid)) { $null } else { $Sid })
        context = @{}
    } | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/agent/chat" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 90
}

function Assert-ToolResult {
    param([object]$Response, [string]$ExpectedTool)
    if ($null -eq $Response.data) { throw "missing response.data" }
    if ($Response.data.toolName -ne $ExpectedTool) {
        throw "expected toolName=$ExpectedTool, actual=$($Response.data.toolName)"
    }
    if ($null -eq $Response.data.success) { throw "missing success field" }
}

function Assert-AgentReplyClean {
    param([object]$Response)
    if ($null -eq $Response.data.reply -or [string]::IsNullOrWhiteSpace($Response.data.reply)) {
        throw "missing agent reply"
    }
    $reply = [string]$Response.data.reply
    $badPatterns = @(
        '"intent"\s*:',
        '"responseType"\s*:',
        '"evidenceList"\s*:',
        '"actionPlan"\s*:',
        '"toolCalls"\s*:',
        '"planTrace"\s*:',
        '^\s*\{.*\}\s*$'
    )
    foreach ($pattern in $badPatterns) {
        if ($reply -match $pattern) {
            throw "agent reply leaks JSON/process field. pattern=$pattern reply=$reply"
        }
    }
}

function Record-Call {
    param([string]$Name, [string]$Type, [scriptblock]$Call)
    try {
        $data = & $Call
        $script:results += New-TestResult -Name $Name -Type $Type -Status "PASS" -Data $data
        return $data
    } catch {
        $script:results += New-TestResult -Name $Name -Type $Type -Status "FAIL" -ErrorMessage $_.Exception.Message
        return $null
    }
}

$results = @()
$logDir = Join-Path $PSScriptRoot "..\logs\agent-core-tool-tests"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Record-Call "tool.get_system_health.enhanced" "tool" {
    $response = Invoke-JsonGet "/api/v1/agent/tools/get_system_health/enhanced?limit=10"
    Assert-ToolResult $response "get_system_health"
    $response
} | Out-Null

$currentState = Record-Call "tool.get_current_simulation_state" "tool" {
    $path = "/api/v1/agent/tools/get_current_simulation_state"
    if (-not [string]::IsNullOrWhiteSpace($Sid)) {
        $path = "${path}?sid=$([uri]::EscapeDataString($Sid))"
    }
    Invoke-JsonGet $path
}

if ($currentState -and $currentState.data -and $currentState.data.session -and [string]::IsNullOrWhiteSpace($Sid)) {
    $Sid = $currentState.data.session.sid
}

if (($currentState -and $currentState.data -and $currentState.data.signals -and $currentState.data.signals.Count -ge 2) `
        -and ([string]::IsNullOrWhiteSpace($StartIntersection) -or [string]::IsNullOrWhiteSpace($EndIntersection))) {
    $StartIntersection = $currentState.data.signals[0].cityflowIntersectionId
    $EndIntersection = $currentState.data.signals[1].cityflowIntersectionId
}

Record-Call "tool.search_knowledge_base" "tool" {
    $response = Invoke-JsonGet "/api/v1/agent/tools/search_knowledge_base?query=Traffic-R&topK=3"
    Assert-ToolResult $response "search_knowledge_base"
    $response
} | Out-Null

Record-Call "tool.audit_configuration_consistency" "tool" {
    $query = ""
    if (-not [string]::IsNullOrWhiteSpace($Sid)) {
        $query = "?sid=$([uri]::EscapeDataString($Sid))"
    }
    $response = Invoke-JsonGet "/api/v1/agent/tools/audit_configuration_consistency$query"
    Assert-ToolResult $response "audit_configuration_consistency"
    $response
} | Out-Null

Record-Call "tool.get_emergency_vehicle_status" "tool" {
    $params = @()
    if (-not [string]::IsNullOrWhiteSpace($Sid)) { $params += "sid=$([uri]::EscapeDataString($Sid))" }
    if (-not [string]::IsNullOrWhiteSpace($VehicleId)) { $params += "vehicleId=$([uri]::EscapeDataString($VehicleId))" }
    $query = if ($params.Count -gt 0) { "?" + ($params -join "&") } else { "" }
    $response = Invoke-JsonGet "/api/v1/agent/tools/get_emergency_vehicle_status$query"
    Assert-ToolResult $response "get_emergency_vehicle_status"
    $response
} | Out-Null

if (-not [string]::IsNullOrWhiteSpace($StartIntersection) -and -not [string]::IsNullOrWhiteSpace($EndIntersection)) {
    Record-Call "tool.draft_emergency_dispatch" "tool" {
        $params = @(
            "startIntersection=$([uri]::EscapeDataString($StartIntersection))",
            "endIntersection=$([uri]::EscapeDataString($EndIntersection))",
            "evId=agent-test-ev",
            "evType=ambulance",
            "priority=1"
        )
        if (-not [string]::IsNullOrWhiteSpace($Sid)) { $params += "sid=$([uri]::EscapeDataString($Sid))" }
        $query = $params -join "&"
        $response = Invoke-JsonGet "/api/v1/agent/tools/draft_emergency_dispatch?$query"
        Assert-ToolResult $response "draft_emergency_dispatch"
        $response
    } | Out-Null
} else {
    $results += New-TestResult -Name "tool.draft_emergency_dispatch" -Type "tool" -Status "SKIP" -ErrorMessage "No start/end intersection available. Pass -StartIntersection and -EndIntersection or start a simulation first."
}

$latestDecisions = Record-Call "tool.get_latest_control_decisions" "tool" {
    $path = "/api/v1/agent/tools/get_latest_control_decisions?limit=1"
    if (-not [string]::IsNullOrWhiteSpace($Sid)) {
        $path = "${path}&sid=$([uri]::EscapeDataString($Sid))"
    }
    Invoke-JsonGet $path
}

if ($latestDecisions -and $latestDecisions.data -and $latestDecisions.data.Count -gt 0) {
    $decisionId = $latestDecisions.data[0].id
    Record-Call "tool.get_decision_trace.enhanced" "tool" {
        $response = Invoke-JsonGet "/api/v1/agent/tools/get_decision_trace/$decisionId/enhanced"
        Assert-ToolResult $response "get_decision_trace"
        $response
    } | Out-Null
} else {
    $results += New-TestResult -Name "tool.get_decision_trace.enhanced" -Type "tool" -Status "SKIP" -ErrorMessage "No control decision found. Start a simulation with strategy decisions first."
}

if (-not $SkipAgentChat) {
    Record-Call "agent.chat.system_health" "agent" {
        $response = Invoke-AgentChat "Check Spring Boot, CityFlow, Traffic-R, WebSocket and database health. Return only the final user-facing conclusion."
        Assert-AgentReplyClean $response
        if (@($response.data.toolCalls | Where-Object { $_.toolName -eq "get_system_health" }).Count -lt 1) {
            throw "agent did not call get_system_health"
        }
        $response
    } | Out-Null

    Record-Call "agent.chat.knowledge" "agent" {
        $response = Invoke-AgentChat "Explain the relationship between Traffic-R and the safety layer. Return only the final user-facing explanation."
        Assert-AgentReplyClean $response
        if (@($response.data.toolCalls | Where-Object { $_.toolName -eq "search_knowledge_base" }).Count -lt 1) {
            throw "agent did not call search_knowledge_base"
        }
        $response
    } | Out-Null

    if (-not [string]::IsNullOrWhiteSpace($StartIntersection) -and -not [string]::IsNullOrWhiteSpace($EndIntersection)) {
        Record-Call "agent.chat.emergency_draft" "agent" {
            $message = "Draft an emergency vehicle dispatch plan from $StartIntersection to $EndIntersection. Return only final user-facing advice and do not output JSON."
            $response = Invoke-AgentChat $message
            Assert-AgentReplyClean $response
            if (@($response.data.toolCalls | Where-Object { $_.toolName -eq "draft_emergency_dispatch" }).Count -lt 1) {
                throw "agent did not call draft_emergency_dispatch"
            }
            $response
        } | Out-Null
    }
}

$summary = [ordered]@{
    baseUrl = $BaseUrl
    sid = $Sid
    startIntersection = $StartIntersection
    endIntersection = $EndIntersection
    pass = ($results | Where-Object { $_.status -eq "PASS" }).Count
    fail = ($results | Where-Object { $_.status -eq "FAIL" }).Count
    skip = ($results | Where-Object { $_.status -eq "SKIP" }).Count
    results = $results
    generatedAt = (Get-Date).ToString("o")
}

$logPath = Join-Path $logDir ("agent-core-tool-test-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")
$summary | ConvertTo-Json -Depth 50 | Set-Content -Path $logPath -Encoding UTF8
$summary | ConvertTo-Json -Depth 8
Write-Host "Detailed test log written to $logPath"

if ($summary.fail -gt 0) {
    exit 1
}
