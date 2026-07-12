param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$SceneId = "jinan_3x4",
    [string]$ControllerType = "fixed-time",
    [double]$Speed = 1.0,
    [double]$WarmupSeconds = 0,
    [int]$RealtimePollAttempts = 10,
    [int]$RealtimePollDelaySeconds = 1,
    [switch]$SkipSimulationCreate,
    [string]$ExistingSid = "",
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"

function New-SafeName {
    param([string]$Name)
    return ($Name -replace '[^a-zA-Z0-9_.-]', '_')
}

function Convert-ToJsonText {
    param($Value, [int]$Depth = 30)
    return ($Value | ConvertTo-Json -Depth $Depth)
}

function Save-Text {
    param([string]$Path, [string]$Text)
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Set-Content -Path $Path -Value $Text -Encoding UTF8
}

function Invoke-TestHttp {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Url,
        $Body = $null
    )

    $started = Get-Date
    $statusCode = $null
    $raw = ""
    $json = $null
    $errorText = $null

    try {
        $params = @{
            Uri = $Url
            Method = $Method
            UseBasicParsing = $true
            TimeoutSec = 60
            Headers = @{
                "Accept" = "application/json"
            }
        }
        if ($null -ne $Body) {
            $params["ContentType"] = "application/json; charset=utf-8"
            $params["Body"] = Convert-ToJsonText $Body
        }
        $response = Invoke-WebRequest @params
        $statusCode = [int]$response.StatusCode
        $raw = [string]$response.Content
    } catch {
        $errorText = $_.Exception.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $raw = $reader.ReadToEnd()
            } catch {
                $raw = ""
            }
        }
    }

    if ($raw -and $raw.Trim().Length -gt 0) {
        try {
            $json = $raw | ConvertFrom-Json
        } catch {
            if (-not $errorText) {
                $errorText = "response is not valid JSON: $($_.Exception.Message)"
            }
        }
    }

    $elapsedMs = [int]((Get-Date) - $started).TotalMilliseconds
    return [ordered]@{
        name = $Name
        method = $Method
        url = $Url
        statusCode = $statusCode
        elapsedMs = $elapsedMs
        success = ($statusCode -ge 200 -and $statusCode -lt 300)
        apiSuccess = if ($null -ne $json -and $json.PSObject.Properties.Name -contains "success") { [bool]$json.success } else { $null }
        body = $json
        raw = $raw
        error = $errorText
    }
}

function Add-Check {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Name,
        [string]$Status,
        [string]$Message
    )
    $Checks.Add([ordered]@{
        name = $Name
        status = $Status
        message = $Message
    }) | Out-Null
}

function Has-Prop {
    param($Object, [string]$Name)
    return ($null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name)
}

function Validate-ApiEnvelope {
    param($Result, [System.Collections.Generic.List[object]]$Checks)

    if (-not $Result.success) {
        Add-Check $Checks "http-2xx" "FAIL" "HTTP status=$($Result.statusCode), error=$($Result.error)"
        return
    }
    Add-Check $Checks "http-2xx" "PASS" "HTTP status=$($Result.statusCode), latency=$($Result.elapsedMs)ms"

    if ($null -eq $Result.body) {
        Add-Check $Checks "json-body" "FAIL" "response body is empty or not JSON"
        return
    }
    Add-Check $Checks "json-body" "PASS" "response body is valid JSON"

    foreach ($field in @("success", "message", "data")) {
        if (Has-Prop $Result.body $field) {
            Add-Check $Checks "api-envelope.$field" "PASS" "contains $field"
        } else {
            Add-Check $Checks "api-envelope.$field" "FAIL" "missing $field"
        }
    }

    if ((Has-Prop $Result.body "success") -and $Result.body.success -eq $false) {
        Add-Check $Checks "api-success" "FAIL" "ApiResponse success=false, message=$($Result.body.message)"
    } elseif (Has-Prop $Result.body "success") {
        Add-Check $Checks "api-success" "PASS" "ApiResponse success=true"
    }
}

function Validate-DataFields {
    param(
        $Result,
        [string[]]$RequiredFields,
        [System.Collections.Generic.List[object]]$Checks
    )

    if ($null -eq $Result.body -or -not (Has-Prop $Result.body "data")) {
        Add-Check $Checks "data-fields" "FAIL" "cannot validate fields because ApiResponse.data is missing"
        return
    }
    $data = $Result.body.data
    if ($null -eq $data) {
        Add-Check $Checks "data-not-null" "WARN" "ApiResponse.data is null"
        return
    }

    foreach ($field in $RequiredFields) {
        if (Has-Prop $data $field) {
            Add-Check $Checks "data.$field" "PASS" "contains $field"
        } else {
            Add-Check $Checks "data.$field" "FAIL" "missing $field"
        }
    }
}

function Validate-ArrayData {
    param($Result, [System.Collections.Generic.List[object]]$Checks)
    if ($null -eq $Result.body -or -not (Has-Prop $Result.body "data")) {
        Add-Check $Checks "array-data" "FAIL" "cannot validate array because ApiResponse.data is missing"
        return
    }
    if ($Result.body.data -is [System.Array]) {
        Add-Check $Checks "array-data" "PASS" "data is array, count=$($Result.body.data.Count)"
    } elseif ($null -eq $Result.body.data) {
        Add-Check $Checks "array-data" "WARN" "data is null"
    } else {
        Add-Check $Checks "array-data" "FAIL" "data is not array"
    }
}

function Get-FirstFieldValue {
    param($Object, [string[]]$CandidateFields)
    if ($null -eq $Object) {
        return $null
    }
    foreach ($field in $CandidateFields) {
        if (Has-Prop $Object $field) {
            $value = $Object.$field
            if ($null -ne $value -and "$value".Length -gt 0) {
                return "$value"
            }
        }
    }
    return $null
}

function Select-FirstIntersectionId {
    param($CurrentState)
    if ($null -eq $CurrentState -or -not (Has-Prop $CurrentState "signals") -or $null -eq $CurrentState.signals) {
        return $null
    }
    foreach ($signal in $CurrentState.signals) {
        $id = Get-FirstFieldValue $signal @("intersectionId", "cityflowIntersectionId")
        if ($id) {
            return $id
        }
    }
    return $null
}

function Select-FirstRoadIdFromIntersection {
    param($IntersectionDetail)
    if ($null -eq $IntersectionDetail -or -not (Has-Prop $IntersectionDetail "roadLinks") -or $null -eq $IntersectionDetail.roadLinks) {
        return $null
    }
    foreach ($link in $IntersectionDetail.roadLinks) {
        $id = Get-FirstFieldValue $link @("fromRoadId", "toRoadId")
        if ($id) {
            return $id
        }
    }
    return $null
}

function Test-AgentChatToolUse {
    param(
        [string]$Name,
        [string]$Message,
        [string]$ExpectedTool,
        [string]$Sid
    )
    $body = @{
        message = $Message
        sessionId = "agent-tool-script-test"
        sid = $Sid
        context = @{}
    }
    $result = Invoke-TestHttp -Name $Name -Method "POST" -Url "$BaseUrl/api/v1/agent/chat" -Body $body
    $checks = [System.Collections.Generic.List[object]]::new()
    Validate-ApiEnvelope $result $checks
    if ($null -ne $result.body -and (Has-Prop $result.body "data") -and $null -ne $result.body.data) {
        $toolNames = @()
        if ((Has-Prop $result.body.data "toolCalls") -and $null -ne $result.body.data.toolCalls) {
            foreach ($call in $result.body.data.toolCalls) {
                if (Has-Prop $call "toolName") {
                    $toolNames += $call.toolName
                }
            }
        }
        if ($toolNames -contains $ExpectedTool) {
            Add-Check $checks "agent.toolCalls.$ExpectedTool" "PASS" "Agent used expected tool: $ExpectedTool"
        } else {
            Add-Check $checks "agent.toolCalls.$ExpectedTool" "FAIL" "Expected tool $ExpectedTool not used. Actual tools: $($toolNames -join ', ')"
        }
    }
    return [ordered]@{
        result = $result
        checks = $checks
    }
}

if (-not $OutputDir -or $OutputDir.IsNullOrWhiteSpace) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDir = Join-Path (Get-Location) "logs/agent-tool-tests/$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$report = [ordered]@{
    startedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    sceneId = $SceneId
    controllerType = $ControllerType
    sid = $ExistingSid
    results = [System.Collections.Generic.List[object]]::new()
    summary = [ordered]@{
        pass = 0
        warn = 0
        fail = 0
    }
}

function Add-TestResult {
    param(
        [string]$Name,
        $HttpResult,
        [System.Collections.Generic.List[object]]$Checks
    )
    $safe = New-SafeName $Name
    Save-Text -Path (Join-Path $OutputDir "$safe.response.json") -Text (Convert-ToJsonText $HttpResult 40)
    $failed = @($Checks | Where-Object { $_.status -eq "FAIL" }).Count
    $warned = @($Checks | Where-Object { $_.status -eq "WARN" }).Count
    if ($failed -gt 0) {
        $status = "FAIL"
        $report.summary.fail++
    } elseif ($warned -gt 0) {
        $status = "WARN"
        $report.summary.warn++
    } else {
        $status = "PASS"
        $report.summary.pass++
    }
    $report.results.Add([ordered]@{
        name = $Name
        status = $status
        statusCode = $HttpResult.statusCode
        elapsedMs = $HttpResult.elapsedMs
        checks = $Checks
        responseFile = "$safe.response.json"
    }) | Out-Null
}

try {
    $health = Invoke-TestHttp -Name "preflight.backend_health" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_system_health?limit=5"
    $healthChecks = [System.Collections.Generic.List[object]]::new()
    Validate-ApiEnvelope $health $healthChecks
    Validate-DataFields $health @("databaseConnected", "tableCounts", "sessionStatusCounts", "services") $healthChecks
    Add-TestResult "preflight.backend_health" $health $healthChecks

    $sid = $ExistingSid
    if (-not $SkipSimulationCreate) {
        $createBody = @{
            sceneId = $SceneId
            speed = $Speed
            warmupSeconds = $WarmupSeconds
            controllerType = $ControllerType
        }
        $create = Invoke-TestHttp -Name "simulation.create" -Method "POST" -Url "$BaseUrl/api/v1/simulations" -Body $createBody
        $createChecks = [System.Collections.Generic.List[object]]::new()
        Validate-ApiEnvelope $create $createChecks
        Validate-DataFields $create @("sid", "sceneId", "status", "controllerType") $createChecks
        if ($null -ne $create.body -and $null -ne $create.body.data -and (Has-Prop $create.body.data "sid")) {
            $sid = $create.body.data.sid
            $report.sid = $sid
            Add-Check $createChecks "simulation.sid" "PASS" "created sid=$sid"
        } else {
            Add-Check $createChecks "simulation.sid" "FAIL" "failed to extract sid from create response"
        }
        Add-TestResult "simulation.create" $create $createChecks

        if ($sid) {
            $start = Invoke-TestHttp -Name "simulation.start" -Method "POST" -Url "$BaseUrl/api/v1/simulations/$sid/start"
            $startChecks = [System.Collections.Generic.List[object]]::new()
            Validate-ApiEnvelope $start $startChecks
            Add-TestResult "simulation.start" $start $startChecks
        }
    }

    $current = $null
    if ($sid) {
        for ($i = 1; $i -le $RealtimePollAttempts; $i++) {
            $current = Invoke-TestHttp -Name "tool.current_state.poll_$i" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_current_simulation_state?sid=$sid"
            $checks = [System.Collections.Generic.List[object]]::new()
            Validate-ApiEnvelope $current $checks
            Validate-DataFields $current @("session", "latestFrame", "persistedFrameCount", "signals") $checks
            $hasFrame = ($null -ne $current.body -and $null -ne $current.body.data -and $null -ne $current.body.data.latestFrame)
            if ($hasFrame) {
                Add-Check $checks "current_state.latestFrame.available" "PASS" "latestFrame is available"
                Add-TestResult "tool.current_state.poll_$i" $current $checks
                break
            }
            Add-Check $checks "current_state.latestFrame.available" "WARN" "latestFrame not available yet"
            Add-TestResult "tool.current_state.poll_$i" $current $checks
            Start-Sleep -Seconds $RealtimePollDelaySeconds
        }
    } else {
        $missingSidChecks = [System.Collections.Generic.List[object]]::new()
        Add-Check $missingSidChecks "simulation.sid" "WARN" "No sid available, realtime tool tests cannot run"
        Add-TestResult "tool.current_state.skipped" ([ordered]@{ statusCode = $null; elapsedMs = 0; success = $false; body = $null; raw = ""; error = "No sid" }) $missingSidChecks
    }

    $intersectionId = $null
    $roadId = $null
    if ($null -ne $current -and $null -ne $current.body -and $null -ne $current.body.data) {
        $intersectionId = Select-FirstIntersectionId $current.body.data
    }

    if ($sid -and $intersectionId) {
        $intersection = Invoke-TestHttp -Name "tool.intersection_detail.valid" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_intersection_detail/$intersectionId?sid=$sid"
        $checks = [System.Collections.Generic.List[object]]::new()
        Validate-ApiEnvelope $intersection $checks
        Validate-DataFields $intersection @("id", "sceneCode", "cityflowId", "latestState", "movements", "phases", "roadLinks") $checks
        if ($null -ne $intersection.body -and $null -ne $intersection.body.data) {
            $roadId = Select-FirstRoadIdFromIntersection $intersection.body.data
            if ($roadId) {
                Add-Check $checks "derived.roadId" "PASS" "derived roadId=$roadId"
            } else {
                Add-Check $checks "derived.roadId" "WARN" "no road id found in roadLinks"
            }
        }
        Add-TestResult "tool.intersection_detail.valid" $intersection $checks
    } else {
        $checks = [System.Collections.Generic.List[object]]::new()
        Add-Check $checks "derived.intersectionId" "WARN" "No intersectionId available from current state"
        Add-TestResult "tool.intersection_detail.skipped" ([ordered]@{ statusCode = $null; elapsedMs = 0; success = $false; body = $null; raw = ""; error = "No intersectionId" }) $checks
    }

    if ($sid -and $roadId) {
        $road = Invoke-TestHttp -Name "tool.road_detail.valid" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_road_detail/$roadId?sid=$sid"
        $checks = [System.Collections.Generic.List[object]]::new()
        Validate-ApiEnvelope $road $checks
        Validate-DataFields $road @("id", "sceneCode", "cityflowId", "fromIntersectionId", "toIntersectionId", "latestState", "lanes") $checks
        Add-TestResult "tool.road_detail.valid" $road $checks
    } else {
        $checks = [System.Collections.Generic.List[object]]::new()
        Add-Check $checks "derived.roadId" "WARN" "No roadId available from intersection detail"
        Add-TestResult "tool.road_detail.skipped" ([ordered]@{ statusCode = $null; elapsedMs = 0; success = $false; body = $null; raw = ""; error = "No roadId" }) $checks
    }

    foreach ($item in @(
        @{ name = "tool.current_state.invalid_sid"; url = "$BaseUrl/api/v1/agent/tools/get_current_simulation_state?sid=missing_sid_for_agent_tool_test"; expected = "error" },
        @{ name = "tool.intersection_detail.invalid_id"; url = "$BaseUrl/api/v1/agent/tools/get_intersection_detail/missing_intersection_for_agent_tool_test?sid=$sid"; expected = "error" },
        @{ name = "tool.road_detail.invalid_id"; url = "$BaseUrl/api/v1/agent/tools/get_road_detail/missing_road_for_agent_tool_test?sid=$sid"; expected = "error" }
    )) {
        $res = Invoke-TestHttp -Name $item.name -Method "GET" -Url $item.url
        $checks = [System.Collections.Generic.List[object]]::new()
        if ($res.success -or ($null -ne $res.body -and (Has-Prop $res.body "success") -and $res.body.success -eq $true)) {
            Add-Check $checks "invalid-input-rejected" "FAIL" "invalid request unexpectedly succeeded"
        } else {
            Add-Check $checks "invalid-input-rejected" "PASS" "invalid request rejected with status=$($res.statusCode), message=$($res.raw)"
        }
        Add-TestResult $item.name $res $checks
    }

    foreach ($item in @(
        @{ name = "tool.latest_control_decisions"; url = "$BaseUrl/api/v1/agent/tools/get_latest_control_decisions?sid=$sid&limit=10" },
        @{ name = "tool.model_inference_log"; url = "$BaseUrl/api/v1/agent/tools/get_model_inference_log?sid=$sid&limit=10" },
        @{ name = "tool.fallback_events"; url = "$BaseUrl/api/v1/agent/tools/get_fallback_events?sid=$sid&limit=10" },
        @{ name = "tool.safety_events"; url = "$BaseUrl/api/v1/agent/tools/get_safety_events?sid=$sid&limit=10" },
        @{ name = "tool.alert_events"; url = "$BaseUrl/api/v1/agent/tools/get_alert_events?limit=10" },
        @{ name = "tool.emergency_events"; url = "$BaseUrl/api/v1/agent/tools/get_emergency_events?sid=$sid&limit=10" }
    )) {
        $res = Invoke-TestHttp -Name $item.name -Method "GET" -Url $item.url
        $checks = [System.Collections.Generic.List[object]]::new()
        Validate-ApiEnvelope $res $checks
        Validate-ArrayData $res $checks
        Add-TestResult $item.name $res $checks
    }

    $decisionId = $null
    $decisionList = Invoke-TestHttp -Name "tool.latest_control_decisions.for_trace" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_latest_control_decisions?sid=$sid&limit=1"
    if ($null -ne $decisionList.body -and $null -ne $decisionList.body.data -and $decisionList.body.data.Count -gt 0) {
        $decisionId = $decisionList.body.data[0].id
    }
    if ($decisionId) {
        $trace = Invoke-TestHttp -Name "tool.decision_trace.valid" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_decision_trace/$decisionId"
        $checks = [System.Collections.Generic.List[object]]::new()
        Validate-ApiEnvelope $trace $checks
        Validate-DataFields $trace @("decision", "traces") $checks
        Add-TestResult "tool.decision_trace.valid" $trace $checks
    } else {
        $checks = [System.Collections.Generic.List[object]]::new()
        Add-Check $checks "decisionId.available" "WARN" "No control decision found; decision trace test skipped"
        Add-TestResult "tool.decision_trace.skipped" ([ordered]@{ statusCode = $null; elapsedMs = 0; success = $true; body = $null; raw = ""; error = "" }) $checks
    }

    foreach ($chatCase in @(
        @{ name = "agent_chat.current_state_requires_tool"; message = "Please query the current realtime traffic simulation state."; tool = "get_current_simulation_state" },
        @{ name = "agent_chat.system_health_requires_tool"; message = "Please query the current backend system health."; tool = "get_system_health" },
        @{ name = "agent_chat.knowledge_requires_tool"; message = "Search the project documentation and explain the Agent tool calling rules."; tool = "search_knowledge_base" },
        @{ name = "agent_chat.diagnosis_requires_tool"; message = "Diagnose whether the current traffic network is congested and cite evidence."; tool = "diagnose_congestion" }
    )) {
        $chat = Test-AgentChatToolUse -Name $chatCase.name -Message $chatCase.message -ExpectedTool $chatCase.tool -Sid $sid
        Add-TestResult $chatCase.name $chat.result $chat.checks
    }

    foreach ($missing in @(
        "diagnose_congestion",
        "detect_signal_anomaly",
        "detect_spillback_risk",
        "get_region_metrics",
        "compare_strategy_metrics",
        "get_fallback_log",
        "get_safety_constraint_log",
        "search_knowledge_base"
    )) {
        $checks = [System.Collections.Generic.List[object]]::new()
        Add-Check $checks "direct-http-endpoint" "WARN" "No /api/v1/agent/tools/$missing endpoint is currently exposed; only Agent chat/orchestrator can exercise this tool."
        Add-TestResult "coverage.direct_endpoint_missing.$missing" ([ordered]@{ statusCode = $null; elapsedMs = 0; success = $true; body = $null; raw = ""; error = "" }) $checks
    }

} finally {
    $report.finishedAt = (Get-Date).ToString("o")
    Save-Text -Path (Join-Path $OutputDir "summary.json") -Text (Convert-ToJsonText $report 60)

    $md = New-Object System.Text.StringBuilder
    [void]$md.AppendLine("# Agent Tool Test Report")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("- Started: $($report.startedAt)")
    [void]$md.AppendLine("- Finished: $($report.finishedAt)")
    [void]$md.AppendLine("- BaseUrl: $BaseUrl")
    [void]$md.AppendLine("- Sid: $($report.sid)")
    [void]$md.AppendLine("- Summary: PASS=$($report.summary.pass), WARN=$($report.summary.warn), FAIL=$($report.summary.fail)")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("| Test | Status | HTTP | Checks | Response |")
    [void]$md.AppendLine("|---|---:|---:|---|---|")
    foreach ($r in $report.results) {
        $checkText = (($r.checks | ForEach-Object { "$($_.status): $($_.name)" }) -join "<br>")
        [void]$md.AppendLine("| $($r.name) | $($r.status) | $($r.statusCode) | $checkText | $($r.responseFile) |")
    }
    Save-Text -Path (Join-Path $OutputDir "summary.md") -Text $md.ToString()
}

Write-Host "Agent tool tests finished."
Write-Host "OutputDir: $OutputDir"
Write-Host "Summary: PASS=$($report.summary.pass), WARN=$($report.summary.warn), FAIL=$($report.summary.fail)"
if ($report.summary.fail -gt 0) {
    exit 1
}
