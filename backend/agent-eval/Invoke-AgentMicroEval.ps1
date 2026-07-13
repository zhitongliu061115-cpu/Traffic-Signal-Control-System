[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$CasesPath,
    [string]$ReportPath,
    [string]$Sid,
    [string[]]$Category,
    [string[]]$CaseId,
    [int]$TimeoutSec = 90,
    [switch]$List,
    [switch]$FailOnError
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($CasesPath)) { $CasesPath = Join-Path $PSScriptRoot "cases.json" }
if ([string]::IsNullOrWhiteSpace($ReportPath)) { $ReportPath = Join-Path $PSScriptRoot "reports\latest.json" }

function Get-PropertyValue($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Test-ContainsAll([string]$Text, $Values) {
    foreach ($value in @($Values)) {
        if (-not $Text.Contains([string]$value, [System.StringComparison]::OrdinalIgnoreCase)) { return $false }
    }
    return $true
}

function Test-ContainsAny([string]$Text, $Values) {
    foreach ($value in @($Values)) {
        if ($Text.Contains([string]$value, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

function Find-RecommendationJson([string]$Text) {
    $matches = [regex]::Matches($Text, '(?s)\{.*?"type"\s*:\s*"(?:signal_adjust|emergency_greenwave)".*?\}')
    foreach ($match in $matches) {
        try { return ($match.Value | ConvertFrom-Json) } catch { }
    }
    return $null
}

function Add-Check([System.Collections.Generic.List[object]]$Checks, [string]$Name, [bool]$Passed, [string]$Detail) {
    $Checks.Add([ordered]@{ name = $Name; passed = $Passed; detail = $Detail })
}

$cases = @((Get-Content -LiteralPath $CasesPath -Raw -Encoding UTF8 | ConvertFrom-Json) | ForEach-Object { $_ })
if ($Category.Count -gt 0) { $cases = @($cases | Where-Object { $Category -contains $_.category }) }
if ($CaseId.Count -gt 0) { $cases = @($cases | Where-Object { $CaseId -contains $_.id }) }

if ($List) {
    $cases | Select-Object id, category, question | Format-Table -AutoSize -Wrap
    exit 0
}

$conversationIds = @{}
$results = [System.Collections.Generic.List[object]]::new()
$endpoint = $BaseUrl.TrimEnd('/') + "/api/v1/agent/chat"

foreach ($case in $cases) {
    $caseSid = Get-PropertyValue $case "sid"
    if ($caseSid -eq "__MISSING_SID__") { $caseSid = if ($Sid) { $Sid } else { "micro-eval-missing-sid" } }
    $group = Get-PropertyValue $case "conversationGroup"
    $conversationId = if ($group -and $conversationIds.ContainsKey($group)) { $conversationIds[$group] } else { $null }
    $body = [ordered]@{
        message = $case.question
        sessionId = "agent-micro-eval"
        sid = $caseSid
        conversationId = $conversationId
        context = if ($null -ne $case.context) { $case.context } else { @{} }
    }

    Write-Host ("[{0}] {1}" -f $case.id, $case.question) -ForegroundColor Cyan
    $started = Get-Date
    try {
        $raw = Invoke-RestMethod -Method Post -Uri $endpoint -ContentType "application/json; charset=utf-8" `
            -Body ($body | ConvertTo-Json -Depth 20 -Compress) -TimeoutSec $TimeoutSec
        $data = if ($null -ne (Get-PropertyValue $raw "data")) { $raw.data } else { $raw }
        if ($group -and $data.conversationId) { $conversationIds[$group] = $data.conversationId }

        $checks = [System.Collections.Generic.List[object]]::new()
        $expect = $case.expect
        $reply = [string]$data.reply
        $toolCalls = @($data.toolCalls)
        $toolNames = @($toolCalls | ForEach-Object { [string]$_.toolName })
        $intent = [string]$data.planTrace.intent

        if ($expect.intentAnyOf) { Add-Check $checks "intent" (@($expect.intentAnyOf) -contains $intent) ("actual={0}" -f $intent) }
        if ($expect.requireToolCall) { Add-Check $checks "tool-required" ($toolCalls.Count -gt 0) ("count={0}" -f $toolCalls.Count) }
        if ($expect.toolAnyOf) { Add-Check $checks "tool-any-of" (@($expect.toolAnyOf | Where-Object { $toolNames -contains $_ }).Count -gt 0) ("actual={0}" -f ($toolNames -join ',')) }
        if ($expect.toolNoneOf) { Add-Check $checks "tool-denylist" (@($expect.toolNoneOf | Where-Object { $toolNames -contains $_ }).Count -eq 0) ("actual={0}" -f ($toolNames -join ',')) }
        if ($expect.replyContainsAll) { Add-Check $checks "reply-contains-all" (Test-ContainsAll $reply $expect.replyContainsAll) ("expected={0}" -f (@($expect.replyContainsAll) -join ',')) }
        if ($expect.replyContainsAny) { Add-Check $checks "reply-contains-any" (Test-ContainsAny $reply $expect.replyContainsAny) ("expected={0}" -f (@($expect.replyContainsAny) -join ',')) }
        if ($expect.replyForbidden) { Add-Check $checks "reply-forbidden" (-not (Test-ContainsAny $reply $expect.replyForbidden)) ("forbidden={0}" -f (@($expect.replyForbidden) -join ',')) }
        foreach ($pattern in @($expect.replyForbiddenRegex)) { Add-Check $checks "secret-pattern" (-not [regex]::IsMatch($reply, $pattern)) ("pattern={0}" -f $pattern) }
        if ($expect.requireEvidenceOnToolSuccess) {
            $successCount = @($toolCalls | Where-Object { $_.status -eq "SUCCESS" }).Count
            Add-Check $checks "evidence-on-success" (($successCount -eq 0) -or @($data.evidence).Count -gt 0) ("successfulTools={0}; evidence={1}" -f $successCount, @($data.evidence).Count)
        }
        if ($expect.requireAuditIds) { Add-Check $checks "audit-ids" (($toolCalls.Count -gt 0) -and @($toolCalls | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.id) }).Count -eq 0) "Every tool call must have an audit ID" }
        if ($expect.requireRecommendationJson) {
            $recommendation = Find-RecommendationJson $reply
            Add-Check $checks "recommendation-json" ($null -ne $recommendation) "Reply must contain parseable recommendation JSON"
            if ($null -ne $recommendation) {
                Add-Check $checks "recommendation-type" ($recommendation.type -eq $expect.recommendationType) ("actual={0}" -f $recommendation.type)
                $expectedStatus = "\u5efa\u8bae-\u5f85\u4eba\u5de5\u786e\u8ba4" | ConvertFrom-Json
                Add-Check $checks "recommendation-status" ($recommendation.status -eq $expectedStatus) ("actual={0}" -f $recommendation.status)
                $requiredFields = @("type", "targets", "recommendation", "basis", "expected_effect", "confidence", "risk", "status")
                Add-Check $checks "recommendation-fields" (@($requiredFields | Where-Object { $null -eq $recommendation.PSObject.Properties[$_] }).Count -eq 0) "Check all 8 required fields"
            }
        }

        $passed = @($checks | Where-Object { -not $_.passed }).Count -eq 0
        $results.Add([ordered]@{
            id = $case.id; category = $case.category; passed = $passed
            durationMs = [int]((Get-Date) - $started).TotalMilliseconds
            question = $case.question; intent = $intent; fallback = [bool]$data.fallback
            toolCalls = $toolCalls; evidenceCount = @($data.evidence).Count
            reply = $reply; checks = $checks
        })
        Write-Host ($(if ($passed) { "  PASS" } else { "  FAIL" })) -ForegroundColor $(if ($passed) { "Green" } else { "Red" })
    } catch {
        $results.Add([ordered]@{
            id = $case.id; category = $case.category; passed = $false
            durationMs = [int]((Get-Date) - $started).TotalMilliseconds
            question = $case.question; error = $_.Exception.Message
            checks = @([ordered]@{ name = "http-request"; passed = $false; detail = $_.Exception.Message })
        })
        Write-Host "  ERROR: $($_.Exception.Message)" -ForegroundColor Red
    }
}

$passedCount = @($results | Where-Object { $_.passed }).Count
$report = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    endpoint = $endpoint
    sidProvided = -not [string]::IsNullOrWhiteSpace($Sid)
    summary = [ordered]@{
        total = $results.Count
        passed = $passedCount
        failed = $results.Count - $passedCount
        passRate = if ($results.Count -eq 0) { 0 } else { [math]::Round(100 * $passedCount / $results.Count, 2) }
    }
    categories = @($results | Group-Object category | ForEach-Object {
        $categoryPassed = @($_.Group | Where-Object { $_.passed }).Count
        [ordered]@{ category = $_.Name; total = $_.Count; passed = $categoryPassed; failed = $_.Count - $categoryPassed }
    })
    results = $results
}

$reportDirectory = Split-Path -Parent $ReportPath
if ($reportDirectory) { New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null }
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
Write-Host ("`nResult: {0}/{1} passed ({2}%)" -f $passedCount, $results.Count, $report.summary.passRate)
Write-Host ("Report: {0}" -f (Resolve-Path $ReportPath))

if ($FailOnError -and $passedCount -ne $results.Count) { exit 1 }
