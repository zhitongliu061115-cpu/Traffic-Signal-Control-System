[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$CasesPath,
    [string]$OutputDir,
    [string]$Sid = "",
    [string]$SceneId = "jinan_3x4",
    [string]$ControllerType = "fixed-time",
    [double]$Speed = 1.0,
    [double]$WarmupSeconds = 0,
    [string]$IntersectionId = "",
    [string]$RoadId = "",
    [string]$DecisionId = "",
    [string]$StartIntersection = "",
    [string]$EndIntersection = "",
    [string]$CompareSids = "",
    [string[]]$Category,
    [string[]]$CaseId,
    [int]$TimeoutSec = 90,
    [int]$RealtimePollAttempts = 15,
    [int]$RealtimePollDelaySeconds = 1,
    [switch]$SkipSimulationCreate,
    [switch]$FailOnSkip,
    [switch]$List
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($CasesPath)) {
    $CasesPath = Join-Path $PSScriptRoot "cases.json"
}

function Has-Property {
    param($Object, [string]$Name)
    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function Get-PropertyValue {
    param($Object, [string]$Name, $Default = $null)
    if (-not (Has-Property $Object $Name)) {
        return $Default
    }
    return $Object.PSObject.Properties[$Name].Value
}

function Convert-ToJsonText {
    param($Value, [int]$Depth = 60)
    return ($Value | ConvertTo-Json -Depth $Depth)
}

function Protect-SensitiveText {
    param([AllowNull()][string]$Text)
    if ($null -eq $Text) {
        return ""
    }
    $protected = $Text
    $protected = $protected -replace '(?i)\b(sk|ak)-[A-Za-z0-9._-]{10,}\b', '$1-***REDACTED***'
    $protected = $protected -replace '\bLTAI[A-Za-z0-9]{12,}\b', '***REDACTED_ACCESS_KEY_ID***'
    $protected = $protected -replace '(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]{10,}', '$1***REDACTED***'
    $protected = $protected -replace '(?i)(Basic\s+)[A-Za-z0-9+/=]{12,}', '$1***REDACTED***'
    $protected = $protected -replace '(?i)("?(?:password|api[_-]?key|access[_-]?key(?:[_-]?(?:id|secret))?|secret|token)"?\s*[:=]\s*")[^"]+("?)', '$1***REDACTED***$2'
    $protected = $protected -replace '(?i)(://[^:/\s]+:)[^@\s/]+(@)', '$1***REDACTED***$2'
    $protected = $protected -replace '(?i)(jdbc:postgresql://[^\s:/]+(?::\d+)?/[^\s?]+\?[^\s"]*password=)[^&\s"]+', '$1***REDACTED***'
    $protected = $protected -replace '(?i)((?:Signature|X-Amz-Signature|SecurityToken|X-Amz-Security-Token)=)[^&\s"''\\]+', '$1***REDACTED***'
    return $protected
}

function Test-ContainsSensitiveText {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    $patterns = @(
        '(?i)\b(sk|ak)-[A-Za-z0-9._-]{10,}\b',
        '\bLTAI[A-Za-z0-9]{12,}\b',
        '(?i)Bearer\s+[A-Za-z0-9._~+/=-]{10,}',
        '(?i)Basic\s+[A-Za-z0-9+/=]{12,}',
        '(?i)"?(?:password|api[_-]?key|access[_-]?key(?:[_-]?(?:id|secret))?|secret|token)"?\s*[:=]\s*"[^"\s]{6,}"',
        '(?i)://[^:/\s]+:[^@\s/]+@',
        '(?i)(?:Signature|X-Amz-Signature|SecurityToken|X-Amz-Security-Token)=[^&\s"''\\]+'
    )
    foreach ($pattern in $patterns) {
        if ([regex]::IsMatch($Text, $pattern)) {
            return $true
        }
    }
    return $false
}

function Read-ResponseBodyUtf8 {
    param($Response)
    if ($null -eq $Response) {
        return ""
    }
    if ((Has-Property $Response "RawContentStream") -and $null -ne $Response.RawContentStream) {
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

function Invoke-JsonHttp {
    param(
        [string]$Method,
        [string]$Url,
        $Body = $null
    )
    $started = Get-Date
    $statusCode = $null
    $raw = ""
    $json = $null
    $errorText = ""
    try {
        $parameters = @{
            Uri = $Url
            Method = $Method
            UseBasicParsing = $true
            TimeoutSec = $TimeoutSec
            Headers = @{ Accept = "application/json" }
        }
        if ($null -ne $Body) {
            $parameters.ContentType = "application/json; charset=utf-8"
            $parameters.Body = Convert-ToJsonText $Body 30
        }
        $response = Invoke-WebRequest @parameters
        $statusCode = [int]$response.StatusCode
        $raw = Read-ResponseBodyUtf8 $response
    } catch {
        $errorText = $_.Exception.Message
        if ($_.Exception.Response) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            } catch { }
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
                $raw = $reader.ReadToEnd()
            } catch { }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($raw)) {
        try {
            $json = $raw | ConvertFrom-Json
        } catch {
            if ([string]::IsNullOrWhiteSpace($errorText)) {
                $errorText = "响应不是有效 JSON"
            }
        }
    }
    return [ordered]@{
        statusCode = $statusCode
        success = ($statusCode -ge 200 -and $statusCode -lt 300)
        elapsedMs = [int]((Get-Date) - $started).TotalMilliseconds
        body = $json
        raw = $raw
        error = $errorText
    }
}

function Add-Check {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Name,
        [bool]$Passed,
        [string]$Detail
    )
    $Checks.Add([ordered]@{
        name = $Name
        passed = $Passed
        detail = Protect-SensitiveText $Detail
    }) | Out-Null
}

function Test-ContainsAll {
    param([string]$Text, $Values)
    foreach ($value in @($Values)) {
        if ($Text.IndexOf([string]$value, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            return $false
        }
    }
    return $true
}

function Test-ContainsAny {
    param([string]$Text, $Values)
    foreach ($value in @($Values)) {
        if ($Text.IndexOf([string]$value, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $true
        }
    }
    return $false
}

function Test-IsJsonObjectOrArray {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    $trimmed = $Text.Trim()
    if (-not ($trimmed.StartsWith('{') -or $trimmed.StartsWith('['))) {
        return $false
    }
    try {
        $null = $trimmed | ConvertFrom-Json
        return $true
    } catch {
        return $false
    }
}

function Expand-TemplateText {
    param([AllowNull()][string]$Text, [hashtable]$Variables)
    if ($null -eq $Text) {
        return ""
    }
    $expanded = $Text
    foreach ($key in $Variables.Keys) {
        $value = if ($null -eq $Variables[$key]) { "" } else { [string]$Variables[$key] }
        $expanded = $expanded.Replace("{{$key}}", $value)
    }
    return $expanded
}

function Expand-Case {
    param($Case, [hashtable]$Variables)
    $json = Convert-ToJsonText $Case 40
    $expanded = Expand-TemplateText $json $Variables
    return ($expanded | ConvertFrom-Json)
}

function Get-FirstTextValue {
    param($Object, [string[]]$Names)
    foreach ($name in $Names) {
        $value = Get-PropertyValue $Object $name
        if ($null -ne $value -and -not [string]::IsNullOrWhiteSpace([string]$value)) {
            return [string]$value
        }
    }
    return ""
}

function Get-SafeResponseObject {
    param($HttpResult)
    if ($null -ne $HttpResult.body) {
        $safeJson = Protect-SensitiveText (Convert-ToJsonText $HttpResult.body 80)
        try {
            return ($safeJson | ConvertFrom-Json)
        } catch {
            return [ordered]@{ raw = $safeJson }
        }
    }
    return [ordered]@{
        statusCode = $HttpResult.statusCode
        error = Protect-SensitiveText $HttpResult.error
        raw = Protect-SensitiveText $HttpResult.raw
    }
}

function Add-QuotedMarkdown {
    param([System.Text.StringBuilder]$Builder, [string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        [void]$Builder.AppendLine("> （空）")
        return
    }
    $normalizedText = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    foreach ($line in ($normalizedText -split "`n")) {
        [void]$Builder.AppendLine("> $line")
    }
}

$suite = Get-Content -LiteralPath $CasesPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ((Get-PropertyValue $suite "schemaVersion") -ne 2) {
    throw "不支持的用例 schemaVersion，期望值为 2"
}
$allCases = @($suite.cases)
$definedTools = @($allCases | ForEach-Object { @($_.expect.toolAnyOf) } | Where-Object { $_ } | Select-Object -Unique)
$missingDefinitions = @($suite.requiredTools | Where-Object { $definedTools -notcontains $_ })
if ($missingDefinitions.Count -gt 0) {
    throw "用例未覆盖注册工具：$($missingDefinitions -join ', ')"
}

if ($List) {
    $allCases | Select-Object id, category, question | Format-Table -AutoSize -Wrap
    Write-Host "`n注册工具覆盖：$($suite.requiredTools.Count)/$($suite.requiredTools.Count)"
    exit 0
}

$selectedCases = $allCases
if ($Category.Count -gt 0) {
    $selectedCases = @($selectedCases | Where-Object { $Category -contains $_.category })
}
if ($CaseId.Count -gt 0) {
    $selectedCases = @($selectedCases | Where-Object { $CaseId -contains $_.id })
}
if ($selectedCases.Count -eq 0) {
    throw "筛选后没有可执行用例"
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDir = Join-Path $PSScriptRoot "reports\$stamp"
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$preflight = [System.Collections.Generic.List[object]]::new()
if ([string]::IsNullOrWhiteSpace($Sid) -and -not $SkipSimulationCreate) {
    Write-Host "未提供 Sid，正在创建并启动测试仿真..." -ForegroundColor Cyan
    $create = Invoke-JsonHttp "POST" "$BaseUrl/api/v1/simulations" ([ordered]@{
        sceneId = $SceneId
        speed = $Speed
        warmupSeconds = $WarmupSeconds
        controllerType = $ControllerType
    })
    $preflight.Add([ordered]@{ name = "simulation.create"; response = Get-SafeResponseObject $create }) | Out-Null
    if ($create.success -and $null -ne $create.body -and $null -ne $create.body.data -and (Has-Property $create.body.data "sid")) {
        $Sid = [string]$create.body.data.sid
        $start = Invoke-JsonHttp "POST" "$BaseUrl/api/v1/simulations/$Sid/start"
        $preflight.Add([ordered]@{ name = "simulation.start"; response = Get-SafeResponseObject $start }) | Out-Null
        if (-not $start.success) {
            Write-Warning "测试仿真启动失败，依赖实时数据的用例可能失败或跳过。"
        }
    } else {
        Write-Warning "测试仿真创建失败，依赖 Sid 的用例将跳过。"
    }
}

$currentState = $null
if (-not [string]::IsNullOrWhiteSpace($Sid)) {
    for ($poll = 1; $poll -le $RealtimePollAttempts; $poll++) {
        $stateResult = Invoke-JsonHttp "GET" "$BaseUrl/api/v1/agent/tools/get_current_simulation_state?sid=$([uri]::EscapeDataString($Sid))"
        if ($stateResult.success -and $null -ne $stateResult.body -and $null -ne $stateResult.body.data) {
            $currentState = $stateResult.body.data
            if ($null -ne $currentState.latestFrame) {
                break
            }
        }
        if ($poll -lt $RealtimePollAttempts) {
            Start-Sleep -Seconds $RealtimePollDelaySeconds
        }
    }
}

$signalIds = @()
if ($null -ne $currentState -and (Has-Property $currentState "signals")) {
    foreach ($signal in @($currentState.signals)) {
        $candidate = Get-FirstTextValue $signal @("intersectionId", "cityflowIntersectionId")
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            $signalIds += $candidate
        }
    }
}
if ([string]::IsNullOrWhiteSpace($IntersectionId) -and $signalIds.Count -gt 0) {
    $IntersectionId = $signalIds[0]
}
if ([string]::IsNullOrWhiteSpace($StartIntersection) -and $signalIds.Count -gt 0) {
    $StartIntersection = $signalIds[0]
}
if ([string]::IsNullOrWhiteSpace($EndIntersection) -and $signalIds.Count -gt 1) {
    $EndIntersection = $signalIds[1]
}

if ([string]::IsNullOrWhiteSpace($RoadId) -and -not [string]::IsNullOrWhiteSpace($IntersectionId)) {
    $intersectionUrl = "$BaseUrl/api/v1/agent/tools/get_intersection_detail/$([uri]::EscapeDataString($IntersectionId))"
    if (-not [string]::IsNullOrWhiteSpace($Sid)) {
        $intersectionUrl += "?sid=$([uri]::EscapeDataString($Sid))"
    }
    $intersectionResult = Invoke-JsonHttp "GET" $intersectionUrl
    if ($intersectionResult.success -and $null -ne $intersectionResult.body -and $null -ne $intersectionResult.body.data) {
        foreach ($link in @($intersectionResult.body.data.roadLinks)) {
            $RoadId = Get-FirstTextValue $link @("fromRoadId", "toRoadId")
            if (-not [string]::IsNullOrWhiteSpace($RoadId)) {
                break
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($DecisionId) -and -not [string]::IsNullOrWhiteSpace($Sid)) {
    for ($poll = 1; $poll -le $RealtimePollAttempts; $poll++) {
        $decisionResult = Invoke-JsonHttp "GET" "$BaseUrl/api/v1/agent/tools/get_latest_control_decisions?sid=$([uri]::EscapeDataString($Sid))&limit=1"
        if ($decisionResult.success -and $null -ne $decisionResult.body -and @($decisionResult.body.data).Count -gt 0) {
            $DecisionId = [string]$decisionResult.body.data[0].id
            break
        }
        if ($poll -lt $RealtimePollAttempts) {
            Start-Sleep -Seconds $RealtimePollDelaySeconds
        }
    }
}
if ([string]::IsNullOrWhiteSpace($CompareSids)) {
    $CompareSids = $Sid
}

$variables = @{
    sid = $Sid
    intersectionId = $IntersectionId
    roadId = $RoadId
    decisionId = $DecisionId
    startIntersection = $StartIntersection
    endIntersection = $EndIntersection
    compareSids = $CompareSids
}

$conversationIds = @{}
$results = [System.Collections.Generic.List[object]]::new()
$runSessionId = "agent-full-eval-v2-" + (Get-Date -Format "yyyyMMddHHmmss")

foreach ($originalCase in $selectedCases) {
    $case = Expand-Case $originalCase $variables
    Write-Host ("[{0}] {1}" -f $case.id, $case.question) -ForegroundColor Cyan

    $missingVariables = @()
    $requiredVariables = if (Has-Property $originalCase "requiredVariables") {
        @($originalCase.requiredVariables | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    } else {
        @()
    }
    foreach ($requiredVariable in $requiredVariables) {
        if (-not $variables.ContainsKey([string]$requiredVariable) -or [string]::IsNullOrWhiteSpace([string]$variables[[string]$requiredVariable])) {
            $missingVariables += [string]$requiredVariable
        }
    }
    if ($missingVariables.Count -gt 0) {
        $reason = "缺少运行变量：$($missingVariables -join ', ')"
        $results.Add([ordered]@{
            id = $case.id
            category = $case.category
            status = "SKIP"
            durationMs = 0
            question = $case.question
            answer = "未执行：$reason"
            intent = ""
            toolCalls = @()
            evidenceCount = 0
            checks = @()
            request = @{}
            response = [ordered]@{ skipped = $true; reason = $reason }
        }) | Out-Null
        Write-Host "  SKIP $reason" -ForegroundColor Yellow
        continue
    }

    $group = [string](Get-PropertyValue $case "conversationGroup" "")
    $sentConversationId = if (-not [string]::IsNullOrWhiteSpace($group) -and $conversationIds.ContainsKey($group)) {
        [string]$conversationIds[$group]
    } else {
        $null
    }
    $caseSid = [string](Get-PropertyValue $case "sid" $Sid)
    $caseContext = Get-PropertyValue $case "context" ([ordered]@{})
    $requestBody = [ordered]@{
        message = $case.question
        sessionId = $runSessionId
        sid = if ([string]::IsNullOrWhiteSpace($caseSid)) { $null } else { $caseSid }
        conversationId = $sentConversationId
        context = $caseContext
    }

    $http = Invoke-JsonHttp "POST" "$BaseUrl/api/v1/agent/chat" $requestBody
    $checks = [System.Collections.Generic.List[object]]::new()
    Add-Check $checks "http-2xx" $http.success "status=$($http.statusCode); latencyMs=$($http.elapsedMs)"
    $apiSuccess = $http.success -and $null -ne $http.body -and (Has-Property $http.body "success") -and $http.body.success -eq $true
    Add-Check $checks "api-envelope-success" $apiSuccess "ApiResponse.success 必须为 true"

    $data = if ($null -ne $http.body -and (Has-Property $http.body "data")) { $http.body.data } else { $null }
    Add-Check $checks "api-data" ($null -ne $data) "ApiResponse.data 必须存在"
    $reply = if ($null -ne $data -and (Has-Property $data "reply")) { [string]$data.reply } else { "" }
    Add-Check $checks "reply-not-empty" (-not [string]::IsNullOrWhiteSpace($reply)) "最终回答不能为空"

    $toolCalls = @()
    if ($null -ne $data -and (Has-Property $data "toolCalls") -and $null -ne $data.toolCalls) {
        $toolCalls = @($data.toolCalls | Where-Object { $null -ne $_ })
    }
    $toolNames = @($toolCalls | ForEach-Object { [string]$_.toolName })
    $evidence = @()
    if ($null -ne $data -and (Has-Property $data "evidence") -and $null -ne $data.evidence) {
        $evidence = @($data.evidence | Where-Object { $null -ne $_ })
    }
    $intent = if ($null -ne $data -and $null -ne $data.planTrace) { [string]$data.planTrace.intent } else { "" }
    $expect = $case.expect

    if ($expect.intentAnyOf) {
        Add-Check $checks "intent-any-of" (@($expect.intentAnyOf) -contains $intent) "expected=$(@($expect.intentAnyOf) -join ','); actual=$intent"
    }
    if ($expect.toolAnyOf) {
        $expectedTools = @($expect.toolAnyOf)
        $matchingTools = @($toolCalls | Where-Object { $expectedTools -contains $_.toolName })
        Add-Check $checks "tool-any-of" ($matchingTools.Count -gt 0) "expected=$($expectedTools -join ','); actual=$($toolNames -join ',')"
        $requiredStatus = [string](Get-PropertyValue $expect "requiredToolStatus" "")
        if ($requiredStatus -eq "SUCCESS") {
            Add-Check $checks "expected-tool-success" (@($matchingTools | Where-Object { $_.status -eq "SUCCESS" }).Count -gt 0) "至少一个预期工具必须执行成功"
        } elseif ($requiredStatus -eq "CALLED") {
            Add-Check $checks "expected-tool-called" ($matchingTools.Count -gt 0) "预期工具必须被调用，允许因测试数据不存在而失败"
        }
    }
    if ($expect.toolNoneOf) {
        $forbiddenTools = @($expect.toolNoneOf)
        Add-Check $checks "tool-denylist" (@($toolNames | Where-Object { $forbiddenTools -contains $_ }).Count -eq 0) "forbidden=$($forbiddenTools -join ','); actual=$($toolNames -join ',')"
    }
    if ($expect.requireNoToolCall) {
        Add-Check $checks "no-tool-call" ($toolCalls.Count -eq 0) "actual=$($toolNames -join ',')"
    }
    if ($expect.requireAuditIds) {
        $missingAuditIds = @($toolCalls | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.id) })
        Add-Check $checks "audit-ids" ($toolCalls.Count -gt 0 -and $missingAuditIds.Count -eq 0) "每个工具调用必须有审计 ID"
    }
    if ($expect.requireEvidenceOnToolSuccess) {
        $successfulCalls = @($toolCalls | Where-Object { $_.status -eq "SUCCESS" })
        Add-Check $checks "evidence-on-tool-success" ($successfulCalls.Count -gt 0 -and $evidence.Count -gt 0) "successfulTools=$($successfulCalls.Count); evidence=$($evidence.Count)"
    }
    if ($expect.replyContainsAll) {
        Add-Check $checks "reply-contains-all" (Test-ContainsAll $reply $expect.replyContainsAll) "expected=$(@($expect.replyContainsAll) -join ',')"
    }
    if ($expect.replyContainsAny) {
        Add-Check $checks "reply-contains-any" (Test-ContainsAny $reply $expect.replyContainsAny) "expected=$(@($expect.replyContainsAny) -join ',')"
    }
    if ($expect.replyForbidden) {
        Add-Check $checks "reply-forbidden" (-not (Test-ContainsAny $reply $expect.replyForbidden)) "forbidden=$(@($expect.replyForbidden) -join ',')"
    }
    $forbiddenRegexes = if (Has-Property $expect "replyForbiddenRegex") {
        @($expect.replyForbiddenRegex | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    } else {
        @()
    }
    foreach ($pattern in $forbiddenRegexes) {
        Add-Check $checks "reply-forbidden-regex" (-not [regex]::IsMatch($reply, [string]$pattern)) "命中禁止模式时失败"
    }
    if ($expect.requireChinese) {
        Add-Check $checks "language-chinese" ([regex]::IsMatch($reply, '[\u4E00-\u9FFF]')) "回答必须包含中文"
    }
    if ($expect.requireMarkdownList) {
        Add-Check $checks "markdown-list" ([regex]::IsMatch($reply, '(?m)^\s*(?:[-*+]|\d+[.、])\s+\S')) "回答必须包含独立换行的 Markdown 列表"
    }
    if ($expect.maxReplyChars) {
        Add-Check $checks "reply-max-length" ($reply.Length -le [int]$expect.maxReplyChars) "actual=$($reply.Length); max=$($expect.maxReplyChars)"
    }
    if ($expect.forbidJsonReply) {
        Add-Check $checks "not-json-reply" (-not (Test-IsJsonObjectOrArray $reply)) "最终回答不能是 JSON 对象或数组"
    }
    if ($expect.forbidCodeFence) {
        Add-Check $checks "no-code-fence" (-not $reply.Contains('```')) "最终回答不能包含代码围栏"
    }
    Add-Check $checks "no-process-field-leak" (-not [regex]::IsMatch($reply, '(?im)(?:^\s*(?:intent|responseType|evidenceList|actionPlan|toolCalls|planTrace|rawPlan)\s*[:：]|"(?:intent|responseType|evidenceList|actionPlan|toolCalls|planTrace|rawPlan)"\s*:)')) "不能泄露规划或工具过程字段"
    Add-Check $checks "no-mojibake" (-not [regex]::IsMatch($reply, '(锟斤拷|�|Ã.|â€|浣犳槸)')) "回答不能包含常见乱码"
    Add-Check $checks "no-sensitive-value" (-not (Test-ContainsSensitiveText $reply)) "回答不能包含疑似密钥、令牌或密码值"

    if ($expect.requireConversationReuse) {
        $returnedConversationId = if ($null -ne $data) { [string]$data.conversationId } else { "" }
        Add-Check $checks "conversation-reused" (-not [string]::IsNullOrWhiteSpace($sentConversationId) -and $returnedConversationId -eq $sentConversationId) "sent=$sentConversationId; returned=$returnedConversationId"
    }
    if (-not [string]::IsNullOrWhiteSpace($group) -and $null -ne $data -and -not [string]::IsNullOrWhiteSpace([string]$data.conversationId)) {
        $conversationIds[$group] = [string]$data.conversationId
    }

    $failedChecks = @($checks | Where-Object { -not $_.passed })
    $status = if ($failedChecks.Count -eq 0) { "PASS" } else { "FAIL" }
    $safeRequest = Convert-ToJsonText $requestBody 30 | Protect-SensitiveText | ConvertFrom-Json
    $results.Add([ordered]@{
        id = $case.id
        category = $case.category
        status = $status
        durationMs = $http.elapsedMs
        question = $case.question
        answer = Protect-SensitiveText $reply
        intent = $intent
        toolCalls = @($toolCalls | ForEach-Object {
            [ordered]@{
                id = $_.id
                toolName = $_.toolName
                status = $_.status
                latencyMs = $_.latencyMs
                errorMessage = Protect-SensitiveText ([string]$_.errorMessage)
            }
        })
        evidenceCount = $evidence.Count
        checks = $checks
        request = $safeRequest
        response = Get-SafeResponseObject $http
    }) | Out-Null
    Write-Host "  $status" -ForegroundColor $(if ($status -eq "PASS") { "Green" } else { "Red" })
}

$passCount = @($results | Where-Object { $_.status -eq "PASS" }).Count
$failCount = @($results | Where-Object { $_.status -eq "FAIL" }).Count
$skipCount = @($results | Where-Object { $_.status -eq "SKIP" }).Count
$actualTools = @($results | ForEach-Object {
    foreach ($toolCall in @($_["toolCalls"])) {
        if ($null -ne $toolCall -and -not [string]::IsNullOrWhiteSpace([string]$toolCall.toolName)) {
            [string]$toolCall.toolName
        }
    }
} | Select-Object -Unique)
$categoryItems = @($results | ForEach-Object {
    [pscustomobject]@{
        category = [string]$_["category"]
        status = [string]$_["status"]
    }
})
$report = [ordered]@{
    schemaVersion = 2
    generatedAt = (Get-Date).ToString("o")
    endpoint = "$BaseUrl/api/v1/agent/chat"
    outputDirectory = (Resolve-Path $OutputDir).Path
    filters = [ordered]@{ categories = @($Category); caseIds = @($CaseId) }
    runtime = [ordered]@{
        sid = $Sid
        intersectionId = $IntersectionId
        roadId = $RoadId
        decisionId = $DecisionId
        startIntersection = $StartIntersection
        endIntersection = $EndIntersection
        compareSids = $CompareSids
    }
    summary = [ordered]@{
        total = $results.Count
        passed = $passCount
        failed = $failCount
        skipped = $skipCount
        passRate = if (($passCount + $failCount) -eq 0) { 0 } else { [math]::Round(100 * $passCount / ($passCount + $failCount), 2) }
    }
    toolCoverage = [ordered]@{
        registered = @($suite.requiredTools)
        called = $actualTools
        notCalled = @($suite.requiredTools | Where-Object { $actualTools -notcontains $_ })
    }
    categories = @($categoryItems | Group-Object category | ForEach-Object {
        [ordered]@{
            category = $_.Name
            total = $_.Count
            passed = @($_.Group | Where-Object { $_.status -eq "PASS" }).Count
            failed = @($_.Group | Where-Object { $_.status -eq "FAIL" }).Count
            skipped = @($_.Group | Where-Object { $_.status -eq "SKIP" }).Count
        }
    })
    preflight = $preflight
    results = $results
}

$reportJsonPath = Join-Path $OutputDir "report.json"
$jsonMarkdownPath = Join-Path $OutputDir "questions-and-json.md"
$answersMarkdownPath = Join-Path $OutputDir "questions-and-answers.md"
$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $reportJsonPath -Encoding UTF8

$jsonMarkdown = New-Object System.Text.StringBuilder
[void]$jsonMarkdown.AppendLine("# Agent 全量测评：问题与结构化 JSON")
[void]$jsonMarkdown.AppendLine("")
[void]$jsonMarkdown.AppendLine("- 生成时间：$($report.generatedAt)")
[void]$jsonMarkdown.AppendLine("- 接口：$($report.endpoint)")
[void]$jsonMarkdown.AppendLine("- 结果：PASS=$passCount，FAIL=$failCount，SKIP=$skipCount，通过率=$($report.summary.passRate)%")
[void]$jsonMarkdown.AppendLine("- 安全说明：以下响应在写入前已对疑似密钥、令牌和密码值脱敏。")
[void]$jsonMarkdown.AppendLine("")
foreach ($result in $results) {
    [void]$jsonMarkdown.AppendLine("## $($result.id) [$($result.status)]")
    [void]$jsonMarkdown.AppendLine("")
    [void]$jsonMarkdown.AppendLine("分类：$($result.category)；耗时：$($result.durationMs) ms")
    [void]$jsonMarkdown.AppendLine("")
    [void]$jsonMarkdown.AppendLine("### 问题")
    [void]$jsonMarkdown.AppendLine("")
    Add-QuotedMarkdown $jsonMarkdown $result.question
    [void]$jsonMarkdown.AppendLine("")
    [void]$jsonMarkdown.AppendLine("### 结构化 JSON 响应")
    [void]$jsonMarkdown.AppendLine("")
    [void]$jsonMarkdown.AppendLine("~~~~json")
    [void]$jsonMarkdown.AppendLine((Convert-ToJsonText $result.response 100))
    [void]$jsonMarkdown.AppendLine("~~~~")
    [void]$jsonMarkdown.AppendLine("")
    [void]$jsonMarkdown.AppendLine("### 断言")
    [void]$jsonMarkdown.AppendLine("")
    if (@($result.checks).Count -eq 0) {
        [void]$jsonMarkdown.AppendLine("- 无（用例未执行）")
    } else {
        foreach ($check in @($result.checks)) {
            $mark = if ($check.passed) { "PASS" } else { "FAIL" }
            $detail = ([string]$check.detail) -replace "[\r\n]+", " "
            [void]$jsonMarkdown.AppendLine("- [$mark] $($check.name)：$detail")
        }
    }
    [void]$jsonMarkdown.AppendLine("")
}
$jsonMarkdown.ToString() | Set-Content -LiteralPath $jsonMarkdownPath -Encoding UTF8

$answersMarkdown = New-Object System.Text.StringBuilder
[void]$answersMarkdown.AppendLine("# Agent 全量测评：问题与纯文字回答")
[void]$answersMarkdown.AppendLine("")
[void]$answersMarkdown.AppendLine("- 生成时间：$($report.generatedAt)")
[void]$answersMarkdown.AppendLine("- 结果：PASS=$passCount，FAIL=$failCount，SKIP=$skipCount，通过率=$($report.summary.passRate)%")
[void]$answersMarkdown.AppendLine("")
foreach ($result in $results) {
    [void]$answersMarkdown.AppendLine("## $($result.id) [$($result.status)]")
    [void]$answersMarkdown.AppendLine("")
    [void]$answersMarkdown.AppendLine("### 问题")
    [void]$answersMarkdown.AppendLine("")
    Add-QuotedMarkdown $answersMarkdown $result.question
    [void]$answersMarkdown.AppendLine("")
    [void]$answersMarkdown.AppendLine("### 纯文字回答")
    [void]$answersMarkdown.AppendLine("")
    Add-QuotedMarkdown $answersMarkdown $result.answer
    [void]$answersMarkdown.AppendLine("")
    $toolSummary = if (@($result.toolCalls).Count -eq 0) { "无" } else { (@($result.toolCalls | ForEach-Object { "$($_.toolName):$($_.status)" }) -join "，") }
    [void]$answersMarkdown.AppendLine("调用工具：$toolSummary；证据数：$($result.evidenceCount)；意图：$($result.intent)")
    [void]$answersMarkdown.AppendLine("")
}
$answersMarkdown.ToString() | Set-Content -LiteralPath $answersMarkdownPath -Encoding UTF8

Write-Host "`nAgent 全量测评完成。" -ForegroundColor Cyan
Write-Host "结果：PASS=$passCount，FAIL=$failCount，SKIP=$skipCount，通过率=$($report.summary.passRate)%"
Write-Host "结构化报告：$jsonMarkdownPath"
Write-Host "纯文字报告：$answersMarkdownPath"
Write-Host "机器报告：$reportJsonPath"

if ($failCount -gt 0 -or ($FailOnSkip -and $skipCount -gt 0)) {
    exit 1
}
