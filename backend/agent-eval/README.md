# 智能体微测

本目录用于对“城市交通信号调度辅助决策智能体”做可重复的小规模功能测评。测试只调用聊天接口，不调用任何信号执行接口，也不会自动启动后端。

## 覆盖范围

| 类别 | 主要检查 |
| --- | --- |
| 知识库与专业知识 | Traffic-R1、MaxPressure、FixedTime、评价指标、知识检索工具 |
| 实时状态 | 路网、路口、道路、健康、推理日志必须走真实工具 |
| 拥堵诊断 | 拥堵归因、信号异常、溢出风险、策略指标对比 |
| 调度与应急 | 建议 JSON、必填字段、人工确认状态、禁止声称已执行 |
| 上下文 | 指定数据忠实复述、多轮 conversationId 复用 |
| 安全边界 | 提示注入、非法控制工具、敏感信息、领域外请求 |
| 异常降级 | 缺失会话、虚假 ID、工具失败时禁止编造 |
| 审计 | 工具调用摘要、证据和审计 ID |

`cases.json` 中的 `__MISSING_SID__` 默认替换为一个故意不存在的会话，用来验证失败降级。传入 `-Sid` 后会替换为真实仿真会话，以测真实数据链路。

## 执行

先在另一个终端按项目现有方式启动后端。测试默认访问 `http://127.0.0.1:8080`。

```powershell
cd backend
./agent-eval/Invoke-AgentMicroEval.ps1 -List
./agent-eval/Invoke-AgentMicroEval.ps1
```

使用真实仿真会话：

```powershell
./agent-eval/Invoke-AgentMicroEval.ps1 -Sid "实际会话 SID"
```

筛选测试并让失败返回非零退出码：

```powershell
./agent-eval/Invoke-AgentMicroEval.ps1 `
  -Category "安全与越权","安全与隐私" `
  -FailOnError
```

指定服务和报告路径：

```powershell
./agent-eval/Invoke-AgentMicroEval.ps1 `
  -BaseUrl "http://127.0.0.1:8080" `
  -ReportPath "./agent-eval/reports/run-001.json"
```

## 结果判定

脚本执行确定性检查：意图、工具白名单、工具是否被调用、证据、审计 ID、关键措辞、敏感信息模式，以及控制建议 JSON 的字段和状态。完整回复与每项断言写入报告，便于人工复核。

建议重点人工检查：拥堵诊断的因果链、建议收益的不确定性、知识库答案的来源、多轮上下文是否过期，以及工具失败时是否杜绝编造。

## 用例扩展

新增用例时至少提供 `id`、`category`、`question` 和 `expect`。常用断言字段：

- `intentAnyOf`：允许的规划意图。
- `toolAnyOf` / `toolNoneOf`：工具选择允许集或禁止集。
- `requireToolCall`：必须调用工具。
- `replyContainsAll` / `replyContainsAny` / `replyForbidden`：文本约束。
- `replyForbiddenRegex`：敏感模式等正则约束。
- `requireEvidenceOnToolSuccess` / `requireAuditIds`：可审计性约束。
- `requireRecommendationJson` / `recommendationType`：控制建议结构约束。
- `conversationGroup`：同组用例复用服务返回的 `conversationId`。

微测结果会受模型版本和实时数据影响。适合作为回归门禁的用例应优先使用工具契约、字段、安全边界等确定性断言；语言质量与交通专业合理性保留人工复核。
