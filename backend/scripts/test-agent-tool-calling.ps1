param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ExistingSid = "",
    [string]$SceneId = "jinan_3x4",
    [string]$ControllerType = "fixed-time",
    [double]$Speed = 1.0,
    [double]$WarmupSeconds = 0,
    [int]$RealtimePollAttempts = 12,
    [int]$RealtimePollDelaySeconds = 1,
    [int]$RetriesPerCase = 1,
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"

function New-SafeName {
    param([string]$Name)
    return ($Name -replace '[^a-zA-Z0-9_.-]', '_')
}

function Convert-ToJsonText {
    param($Value, [int]$Depth = 40)
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

function Read-ResponseBodyUtf8 {
    param($Response)
    if ($null -eq $Response) {
        return ""
    }
    if ($Response.PSObject.Properties.Name -contains "RawContentStream" -and $null -ne $Response.RawContentStream) {
        try {
            if ($Response.RawContentStream.CanSeek) {
                $Response.RawContentStream.Position = 0
            }
            $memory = New-Object System.IO.MemoryStream
            $Response.RawContentStream.CopyTo($memory)
            return [System.Text.Encoding]::UTF8.GetString($memory.ToArray())
        } catch {
            return [string]$Response.Content
        }
    }
    return [string]$Response.Content
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
            TimeoutSec = 90
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
        $raw = Read-ResponseBodyUtf8 $response
    } catch {
        $errorText = $_.Exception.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream(), [System.Text.Encoding]::UTF8)
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

    return [ordered]@{
        name = $Name
        method = $Method
        url = $Url
        statusCode = $statusCode
        elapsedMs = [int]((Get-Date) - $started).TotalMilliseconds
        success = ($statusCode -ge 200 -and $statusCode -lt 300)
        apiSuccess = if ($null -ne $json -and (Has-Prop $json "success")) { [bool]$json.success } else { $null }
        body = $json
        raw = $raw
        error = $errorText
    }
}

function Has-Prop {
    param($Object, [string]$Name)
    return ($null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name)
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

function Get-ToolNames {
    param($AgentData)
    $names = @()
    if ($null -ne $AgentData -and (Has-Prop $AgentData "toolCalls") -and $null -ne $AgentData.toolCalls) {
        foreach ($call in $AgentData.toolCalls) {
            if (Has-Prop $call "toolName") {
                $names += [string]$call.toolName
            }
        }
    }
    return $names
}

function Get-ToolStatusMap {
    param($AgentData)
    $items = @()
    if ($null -ne $AgentData -and (Has-Prop $AgentData "toolCalls") -and $null -ne $AgentData.toolCalls) {
        foreach ($call in $AgentData.toolCalls) {
            $items += [ordered]@{
                toolName = if (Has-Prop $call "toolName") { $call.toolName } else { "" }
                status = if (Has-Prop $call "status") { $call.status } else { "" }
                errorMessage = if (Has-Prop $call "errorMessage") { $call.errorMessage } else { "" }
                arguments = if (Has-Prop $call "arguments") { $call.arguments } else { @{} }
            }
        }
    }
    return $items
}

function Contains-Any {
    param([string[]]$Values, [string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        if ($Values -contains $candidate) {
            return $true
        }
    }
    return $false
}

function Get-FirstSignalId {
    param($CurrentState)
    if ($null -eq $CurrentState -or -not (Has-Prop $CurrentState "signals") -or $null -eq $CurrentState.signals) {
        return ""
    }
    foreach ($signal in $CurrentState.signals) {
        if ((Has-Prop $signal "intersectionId") -and $signal.intersectionId) {
            return [string]$signal.intersectionId
        }
    }
    return ""
}

function Get-FirstRoadId {
    param($IntersectionDetail)
    if ($null -eq $IntersectionDetail -or -not (Has-Prop $IntersectionDetail "roadLinks") -or $null -eq $IntersectionDetail.roadLinks) {
        return ""
    }
    foreach ($link in $IntersectionDetail.roadLinks) {
        if ((Has-Prop $link "fromRoadId") -and $link.fromRoadId) {
            return [string]$link.fromRoadId
        }
        if ((Has-Prop $link "toRoadId") -and $link.toRoadId) {
            return [string]$link.toRoadId
        }
    }
    return ""
}

function Ensure-Simulation {
    if ($ExistingSid -and $ExistingSid.Trim().Length -gt 0) {
        return $ExistingSid.Trim()
    }

    $createBody = @{
        sceneId = $SceneId
        speed = $Speed
        warmupSeconds = $WarmupSeconds
        controllerType = $ControllerType
    }
    $create = Invoke-TestHttp -Name "simulation.create" -Method "POST" -Url "$BaseUrl/api/v1/simulations" -Body $createBody
    Save-Text -Path (Join-Path $OutputDir "simulation.create.response.json") -Text (Convert-ToJsonText $create 60)
    if (-not $create.success -or $null -eq $create.body -or $null -eq $create.body.data -or -not (Has-Prop $create.body.data "sid")) {
        throw "Cannot create simulation. See simulation.create.response.json"
    }
    $sid = [string]$create.body.data.sid
    $start = Invoke-TestHttp -Name "simulation.start" -Method "POST" -Url "$BaseUrl/api/v1/simulations/$sid/start"
    Save-Text -Path (Join-Path $OutputDir "simulation.start.response.json") -Text (Convert-ToJsonText $start 60)
    if (-not $start.success) {
        throw "Cannot start simulation sid=$sid. See simulation.start.response.json"
    }
    return $sid
}

function Wait-CurrentState {
    param([string]$Sid)
    $last = $null
    for ($i = 1; $i -le $RealtimePollAttempts; $i++) {
        $last = Invoke-TestHttp -Name "current_state.poll_$i" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_current_simulation_state?sid=${Sid}"
        Save-Text -Path (Join-Path $OutputDir "current_state.poll_$i.response.json") -Text (Convert-ToJsonText $last 60)
        if ($last.success -and $null -ne $last.body -and $null -ne $last.body.data -and $null -ne $last.body.data.latestFrame) {
            return $last.body.data
        }
        Start-Sleep -Seconds $RealtimePollDelaySeconds
    }
    throw "No realtime current state available for sid=$Sid"
}

function Invoke-AgentCase {
    param(
        $Case,
        [string]$Sid,
        [hashtable]$Context
    )
    $attempts = [Math]::Max(1, $RetriesPerCase)
    $best = $null
    for ($attempt = 1; $attempt -le $attempts; $attempt++) {
        $body = @{
            message = $Case.message
            sessionId = "agent-tool-calling-test"
            sid = $Sid
            context = $Context
        }
        $result = Invoke-TestHttp -Name "$($Case.name).attempt_$attempt" -Method "POST" -Url "$BaseUrl/api/v1/agent/chat" -Body $body
        $checks = [System.Collections.Generic.List[object]]::new()
        if ($result.success) {
            Add-Check $checks "http-2xx" "PASS" "HTTP status=$($result.statusCode), latency=$($result.elapsedMs)ms"
        } else {
            Add-Check $checks "http-2xx" "FAIL" "HTTP status=$($result.statusCode), error=$($result.error)"
        }

        $data = $null
        if ($null -ne $result.body -and (Has-Prop $result.body "data")) {
            $data = $result.body.data
            Add-Check $checks "api-envelope.data" "PASS" "ApiResponse.data exists"
        } else {
            Add-Check $checks "api-envelope.data" "FAIL" "ApiResponse.data is missing"
        }

        $toolNames = Get-ToolNames $data
        $expected = [string[]]$Case.expectedAny
        if (Contains-Any $toolNames $expected) {
            Add-Check $checks "expected-tool" "PASS" "Expected one of [$($expected -join ', ')], actual [$($toolNames -join ', ')]"
        } else {
            Add-Check $checks "expected-tool" "FAIL" "Expected one of [$($expected -join ', ')], actual [$($toolNames -join ', ')]"
        }

        if ($Case.forbiddenAny) {
            $forbidden = [string[]]$Case.forbiddenAny
            if (Contains-Any $toolNames $forbidden) {
                Add-Check $checks "forbidden-tool" "FAIL" "Forbidden tool appeared. forbidden=[$($forbidden -join ', ')], actual=[$($toolNames -join ', ')]"
            } else {
                Add-Check $checks "forbidden-tool" "PASS" "No forbidden tools appeared"
            }
        }

        if ($Case.mustNeedTools) {
            if ($null -ne $data -and (Has-Prop $data "planTrace") -and $null -ne $data.planTrace -and $data.planTrace.needsTools -eq $true) {
                Add-Check $checks "planTrace.needsTools" "PASS" "needsTools=true"
            } else {
                Add-Check $checks "planTrace.needsTools" "FAIL" "needsTools is not true"
            }
        }

        if ($Case.requiredStatusSuccess) {
            $statuses = Get-ToolStatusMap $data
            $matching = @($statuses | Where-Object { $expected -contains $_.toolName })
            if ($matching.Count -gt 0 -and @($matching | Where-Object { $_.status -eq "SUCCESS" }).Count -gt 0) {
                Add-Check $checks "expected-tool-success" "PASS" "At least one expected tool succeeded"
            } else {
                Add-Check $checks "expected-tool-success" "FAIL" "No expected tool succeeded"
            }
        }

        $failed = @($checks | Where-Object { $_.status -eq "FAIL" }).Count
        $caseResult = [ordered]@{
            name = $Case.name
            attempt = $attempt
            status = if ($failed -gt 0) { "FAIL" } else { "PASS" }
            statusCode = $result.statusCode
            elapsedMs = $result.elapsedMs
            expectedAny = $expected
            actualTools = $toolNames
            toolStatuses = Get-ToolStatusMap $data
            intent = if ($null -ne $data -and (Has-Prop $data "planTrace") -and $null -ne $data.planTrace) { $data.planTrace.intent } else { "" }
            needsTools = if ($null -ne $data -and (Has-Prop $data "planTrace") -and $null -ne $data.planTrace) { $data.planTrace.needsTools } else { $null }
            evidenceCount = if ($null -ne $data -and (Has-Prop $data "evidence") -and $null -ne $data.evidence) { $data.evidence.Count } else { 0 }
            checks = $checks
            response = $result
        }
        $safe = New-SafeName "$($Case.name).attempt_$attempt"
        Save-Text -Path (Join-Path $OutputDir "$safe.response.json") -Text (Convert-ToJsonText $caseResult 80)
        $best = $caseResult
        if ($failed -eq 0) {
            return $caseResult
        }
    }
    return $best
}

if (-not $OutputDir -or $OutputDir.Trim().Length -eq 0) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDir = Join-Path (Get-Location) "logs/agent-tool-calling-tests/$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$sid = Ensure-Simulation
$currentState = Wait-CurrentState -Sid $sid
$intersectionId = Get-FirstSignalId $currentState
$roadId = ""
if ($intersectionId) {
    $intersectionProbe = Invoke-TestHttp -Name "probe.intersection_detail" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_intersection_detail/${intersectionId}?sid=${sid}"
    Save-Text -Path (Join-Path $OutputDir "probe.intersection_detail.response.json") -Text (Convert-ToJsonText $intersectionProbe 80)
    if ($intersectionProbe.success -and $null -ne $intersectionProbe.body -and $null -ne $intersectionProbe.body.data) {
        $roadId = Get-FirstRoadId $intersectionProbe.body.data
    }
}

$decisionId = ""
$decisionProbe = Invoke-TestHttp -Name "probe.latest_decisions" -Method "GET" -Url "$BaseUrl/api/v1/agent/tools/get_latest_control_decisions?sid=${sid}&limit=1"
Save-Text -Path (Join-Path $OutputDir "probe.latest_decisions.response.json") -Text (Convert-ToJsonText $decisionProbe 80)
if ($decisionProbe.success -and $null -ne $decisionProbe.body -and $null -ne $decisionProbe.body.data -and $decisionProbe.body.data.Count -gt 0) {
    $decisionId = [string]$decisionProbe.body.data[0].id
}

$context = @{
    sid = $sid
    intersectionId = $intersectionId
    roadId = $roadId
    decisionId = $decisionId
    realtimeDataPolicy = "Realtime traffic status must come from backend tool results."
}

$cases = [System.Collections.Generic.List[object]]::new()
$cases.Add([ordered]@{
    name = "current_state"
    message = "Please check the current realtime traffic network state for sid $sid."
    expectedAny = @("get_current_simulation_state")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "intersection_detail"
    message = "Please inspect intersection $intersectionId in the current simulation $sid, including phase and movement status."
    expectedAny = @("get_intersection_detail")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "road_detail"
    message = "Please inspect road $roadId in simulation $sid, including speed, queue and lane information."
    expectedAny = @("get_road_detail")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "latest_control_decisions"
    message = "Please query the latest control decisions for simulation $sid."
    expectedAny = @("get_latest_control_decisions")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
if ($decisionId) {
    $cases.Add([ordered]@{
        name = "decision_trace"
        message = "Please trace control decision $decisionId and explain the decision chain."
        expectedAny = @("get_decision_trace")
        mustNeedTools = $true
        requiredStatusSuccess = $true
    }) | Out-Null
}
$cases.Add([ordered]@{
    name = "system_health"
    message = "Please check current backend system health, including database, CityFlow and model service health."
    expectedAny = @("get_system_health")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "model_inference_log"
    message = "Please query Traffic-R model inference logs for simulation $sid."
    expectedAny = @("get_model_inference_log")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "knowledge_search"
    message = "Search the project documentation for Agent tool calling rules and summarize the rule."
    expectedAny = @("search_knowledge_base")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "diagnose_congestion"
    message = "Diagnose whether simulation $sid has congestion and cite backend evidence."
    expectedAny = @("diagnose_congestion")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "signal_anomaly"
    message = "Detect whether intersection $intersectionId in simulation $sid has a signal anomaly or suspicious phase behavior."
    expectedAny = @("detect_signal_anomaly")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "spillback_risk"
    message = "Detect downstream spillback risk for road $roadId in simulation $sid."
    expectedAny = @("detect_spillback_risk")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "safety_constraint_log"
    message = "Please query safety constraint trigger logs for simulation $sid and intersection $intersectionId."
    expectedAny = @("get_safety_constraint_log", "get_safety_events")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "fallback_log"
    message = "Please query strategy fallback logs for simulation $sid and intersection $intersectionId."
    expectedAny = @("get_fallback_log", "get_fallback_events")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "region_metrics"
    message = "Please compute region traffic metrics for simulation $sid and intersections $intersectionId."
    expectedAny = @("get_region_metrics")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "strategy_compare"
    message = "Compare strategy metrics for simulation $sid and explain whether fixed-time performs well."
    expectedAny = @("compare_strategy_metrics", "get_region_metrics")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null
$cases.Add([ordered]@{
    name = "emergency_events"
    message = "Please query emergency vehicle or emergency dispatch events for simulation $sid."
    expectedAny = @("get_emergency_events")
    mustNeedTools = $true
    requiredStatusSuccess = $true
}) | Out-Null

$report = [ordered]@{
    startedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    sid = $sid
    intersectionId = $intersectionId
    roadId = $roadId
    decisionId = $decisionId
    summary = [ordered]@{
        pass = 0
        warn = 0
        fail = 0
    }
    results = [System.Collections.Generic.List[object]]::new()
}

foreach ($case in $cases) {
    if (($case.name -eq "intersection_detail" -or $case.name -eq "signal_anomaly" -or $case.name -eq "safety_constraint_log" -or $case.name -eq "fallback_log" -or $case.name -eq "region_metrics") -and -not $intersectionId) {
        $report.summary.warn++
        $report.results.Add([ordered]@{ name = $case.name; status = "WARN"; reason = "No intersectionId available" }) | Out-Null
        continue
    }
    if (($case.name -eq "road_detail" -or $case.name -eq "spillback_risk") -and -not $roadId) {
        $report.summary.warn++
        $report.results.Add([ordered]@{ name = $case.name; status = "WARN"; reason = "No roadId available" }) | Out-Null
        continue
    }
    $caseResult = Invoke-AgentCase -Case $case -Sid $sid -Context $context
    $response = $caseResult.response
    $compact = [ordered]@{
        name = $caseResult.name
        status = $caseResult.status
        statusCode = $caseResult.statusCode
        elapsedMs = $caseResult.elapsedMs
        expectedAny = $caseResult.expectedAny
        actualTools = $caseResult.actualTools
        toolStatuses = $caseResult.toolStatuses
        intent = $caseResult.intent
        needsTools = $caseResult.needsTools
        evidenceCount = $caseResult.evidenceCount
        checks = $caseResult.checks
        responseFile = "$(New-SafeName "$($caseResult.name).attempt_$($caseResult.attempt)").response.json"
    }
    if ($caseResult.status -eq "PASS") {
        $report.summary.pass++
    } else {
        $report.summary.fail++
    }
    $report.results.Add($compact) | Out-Null
}

$report.finishedAt = (Get-Date).ToString("o")
Save-Text -Path (Join-Path $OutputDir "summary.json") -Text (Convert-ToJsonText $report 80)

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# Agent Tool Calling Test Report")
[void]$md.AppendLine("")
[void]$md.AppendLine("- Started: $($report.startedAt)")
[void]$md.AppendLine("- Finished: $($report.finishedAt)")
[void]$md.AppendLine("- BaseUrl: $BaseUrl")
[void]$md.AppendLine("- Sid: $sid")
[void]$md.AppendLine("- IntersectionId: $intersectionId")
[void]$md.AppendLine("- RoadId: $roadId")
[void]$md.AppendLine("- DecisionId: $decisionId")
[void]$md.AppendLine("- Summary: PASS=$($report.summary.pass), WARN=$($report.summary.warn), FAIL=$($report.summary.fail)")
[void]$md.AppendLine("")
[void]$md.AppendLine("| Case | Status | Expected | Actual tools | Tool statuses | Evidence | Response |")
[void]$md.AppendLine("|---|---:|---|---|---|---:|---|")
foreach ($r in $report.results) {
    $expected = if ($r.expectedAny) { ($r.expectedAny -join ", ") } else { "" }
    $actual = if ($r.actualTools) { ($r.actualTools -join ", ") } else { "" }
    $statuses = if ($r.toolStatuses) { (($r.toolStatuses | ForEach-Object { "$($_.toolName):$($_.status)" }) -join "<br>") } else { $r.reason }
    [void]$md.AppendLine("| $($r.name) | $($r.status) | $expected | $actual | $statuses | $($r.evidenceCount) | $($r.responseFile) |")
}
Save-Text -Path (Join-Path $OutputDir "summary.md") -Text $md.ToString()

Write-Host "Agent tool calling tests finished."
Write-Host "OutputDir: $OutputDir"
Write-Host "Summary: PASS=$($report.summary.pass), WARN=$($report.summary.warn), FAIL=$($report.summary.fail)"
if ($report.summary.fail -gt 0) {
    exit 1
}
