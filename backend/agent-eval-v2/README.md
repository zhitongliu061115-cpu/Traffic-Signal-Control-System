# Agent 全量 API 测评 v2

本目录是一套独立测评，不修改也不依赖旧测评脚本。它通过真实的 `POST /api/v1/agent/chat` 请求测试模型规划、工具选择、工具执行、知识库、语言与格式、上下文、安全边界和异常降级。

## 文件

- `Invoke-AgentFullEval.ps1`：测评执行器。
- `cases.json`：用例、断言和 22 个注册工具的覆盖清单。
- `reports/<时间戳>/questions-and-json.md`：每个问题及脱敏后的完整 API JSON 响应。
- `reports/<时间戳>/questions-and-answers.md`：每个问题及从响应中解析出的纯文字回答。
- `reports/<时间戳>/report.json`：机器可读的完整断言、分类统计和工具覆盖率。

## 前置条件

1. 后端、数据库及需要测试的外部服务已经启动。
2. Agent LLM 已启用并正确配置。不要把任何密钥写入脚本或用例文件。
3. 使用 PowerShell 7（`pwsh`）执行脚本。
4. 当前全量运行包含 32 次聊天请求；每次聊天通常包含规划和回答两次模型调用，需要预留调用时间和模型额度。

## 运行

在项目根目录执行。

### 自动创建测试仿真

未提供 `Sid` 时，脚本会调用仿真 API 创建并启动一个测试会话，再自动发现路口、道路和最近决策 ID。

```powershell
pwsh ./backend/agent-eval-v2/Invoke-AgentFullEval.ps1
```

自动创建的仿真不会由脚本自动删除或停止，便于复查测试数据。

### 使用已有仿真

已有运行中的仿真时，建议显式传入 `Sid`，结果更稳定，也不会创建新会话。

```powershell
pwsh ./backend/agent-eval-v2/Invoke-AgentFullEval.ps1 `
  -Sid "实际仿真 SID"
```

若自动发现失败，可以显式补充动态 ID：

```powershell
pwsh ./backend/agent-eval-v2/Invoke-AgentFullEval.ps1 `
  -Sid "实际仿真 SID" `
  -IntersectionId "实际路口 ID" `
  -RoadId "实际道路 ID" `
  -DecisionId "实际决策 UUID" `
  -StartIntersection "起点路口 ID" `
  -EndIntersection "终点路口 ID" `
  -CompareSids "会话一,会话二" `
  -FailOnSkip
```

### 仅查看用例

`-List` 不调用后端，也不生成报告。

```powershell
pwsh ./backend/agent-eval-v2/Invoke-AgentFullEval.ps1 -List
```

### 按类别或用例执行

```powershell
pwsh ./backend/agent-eval-v2/Invoke-AgentFullEval.ps1 `
  -Sid "实际仿真 SID" `
  -Category "语言规范化","输出格式","知识库"

pwsh ./backend/agent-eval-v2/Invoke-AgentFullEval.ps1 `
  -Sid "实际仿真 SID" `
  -CaseId "TOOL-001","CTX-001","SAFE-001"
```

### 指定服务和输出目录

```powershell
pwsh ./backend/agent-eval-v2/Invoke-AgentFullEval.ps1 `
  -BaseUrl "http://127.0.0.1:8080" `
  -Sid "实际仿真 SID" `
  -OutputDir "./backend/agent-eval-v2/reports/manual-run-001"
```

## 退出码

- `0`：没有失败用例；默认允许因缺少动态 ID 产生的 `SKIP`。
- `1`：至少一个断言失败；使用 `-FailOnSkip` 时，存在 `SKIP` 也返回 1。

完整回归或 CI 应使用 `-FailOnSkip`，避免未执行的工具被误认为通过。

## 如何分析结果

先看控制台或 `report.json.summary`：

- `PASS`：本用例全部确定性断言通过。
- `FAIL`：至少一个断言失败，应继续查看失败断言。
- `SKIP`：缺少 `Sid`、路口、道路、决策或应急起终点等动态变量，不能代表能力通过。

然后按以下顺序定位：

1. 查看 `questions-and-answers.md`，人工检查答案是否专业、简洁、忠于证据，是否存在不合理因果关系或过度确定的结论。
2. 查看 `questions-and-json.md` 中同一用例的 `toolCalls`、`evidence`、`planTrace` 和失败断言。
3. `tool-any-of` 失败通常表示模型规划或工具选择错误。
4. `expected-tool-success` 失败表示工具被选中但执行失败，优先检查 `errorMessage`、`sid`、动态 ID、数据库和外部服务。
5. `evidence-on-tool-success` 失败表示工具成功但响应缺少证据，属于可追溯性问题。
6. `intent-any-of` 失败表示意图分类不符合用例预期；如果工具和答案均正确，可评估是否需要放宽允许意图。
7. `no-process-field-leak`、`not-json-reply`、`no-code-fence`、`markdown-list` 失败属于输出格式问题。
8. `language-chinese`、`no-mojibake`、长度断言失败属于语言规范化问题。
9. `conversation-reused` 只验证会话 ID 复用；`CTX-002-B` 同时要求模型正确恢复上一轮分析对象，因此能区分“消息归档到同一会话”和“真正使用历史上下文”。
10. `no-sensitive-value` 失败表示原始回答疑似包含敏感值。报告落盘前会脱敏，但仍应立即检查后端日志和提示词边界。

## 建议的稳定性判定

模型输出具有随机性。正式验收时建议对相同版本连续运行 3 次，并分别统计：

- 确定性契约：HTTP、API 包装、工具名、工具状态、审计 ID、证据、敏感信息，目标应为 100%。
- 语言与格式：中文、列表、长度、过程字段泄露，目标应为 100%。
- 专业质量：因果链、建议合理性、不确定性表达，结合纯文字报告人工评分。
- 多轮上下文：必须在不同会话和不同分析对象下重复验证，防止模型靠问题中的显式 ID 或偶然猜中。

不要只看总通过率。工具未调用、用例跳过或上下文只复用了 ID 都可能产生表面正常、实际能力缺失的结果。
