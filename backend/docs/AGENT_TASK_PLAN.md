# Agent 任务规划文档

更新时间：2026-07-11

本文档是后续 Agent 后端开发的任务规划和工具设计依据。目标是把 Agent 从“模型聊天入口”升级为“基于真实系统数据、项目知识库和可审计工具调用的交通调度辅助智能体”。

## 1. 设计结论

当前已经实现了一批数据库查询接口，但它们偏底层、偏表查询。后续面向 Agent 暴露的工具不应继续按数据库表拆分，而应按“Agent 能完成什么任务”设计。

最终工具体系分三层：

```text
Agent 可见语义化工具
  -> 后端业务查询 / 分析 / 草案服务
  -> Repository / SQL / 外部服务调用
```

原则：

- Agent 不直接访问数据库，不直接拼 SQL。
- Agent 可见工具要少而清晰，优先表达业务意图。
- 日志类、审计类、表级查询可以保留为后端内部能力，不默认暴露给模型。
- 所有工具调用必须可审计：记录会话、消息、工具名、参数、结果摘要、状态、耗时和错误。
- 所有控制类工具只生成草案或建议，不直接执行信号控制。
- 实时交通状态来自 CityFlow / PostgreSQL 真实数据；规范、算法、部署等知识来自项目知识库。

## 2. 当前已实现能力

### 2.1 Agent 可见查询工具

当前已通过 `/api/v1/agent/tools/**` 暴露：

| 工具名 | 状态 | 当前作用 | 后续定位 |
| --- | --- | --- | --- |
| `get_current_simulation_state` | 已实现 | 查询当前或指定仿真会话整体状态、最新帧、信号状态 | 保留为核心工具 |
| `get_intersection_detail` | 已实现 | 查询指定路口相位、movement 状态、相位列表、roadLink | 保留为核心工具，后续补 nearby vehicles |
| `get_road_detail` | 已实现 | 查询道路基础信息、lane 列表、最新道路快照 | 保留为核心工具 |
| `get_latest_control_decisions` | 已实现 | 查询最近控制决策 | 保留为核心工具 |
| `get_decision_trace` | 已实现基础版 | 查询指定决策和 trace | 后续增强为聚合 Traffic-R、安全层、fallback、执行结果 |
| `get_system_health` | 已实现基础版 | 查询数据库视角健康摘要 | 后续增强为 Spring Boot、CityFlow、Traffic-R、WebSocket、数据库统一健康 |
| `get_model_inference_log` | 已实现 | 查询 Traffic-R 推理日志和逐路口结果 | 保留，主要用于调试和决策追踪 |
| `get_fallback_events` | 已实现 | 查询策略 fallback 事件 | 后续作为内部证据，减少模型直接使用 |
| `get_safety_events` | 已实现 | 查询安全约束事件 | 后续作为内部证据，减少模型直接使用 |
| `get_alert_events` | 已实现 | 查询告警事件 | 后续并入系统健康或诊断结果 |
| `get_emergency_events` | 已实现主事件查询 | 查询应急事件主记录 | 后续升级为 `get_emergency_vehicle_status` |

这些工具支持可选 `messageId` 参数。传入后会自动写入 `agent_tool_call`。

### 2.2 Agent 会话与审计

当前已实现：

| 能力 | 接口 | 状态 |
| --- | --- | --- |
| 创建 Agent 会话 | `POST /api/v1/agent/conversations` | 已实现 |
| 查询 Agent 会话 | `GET /api/v1/agent/conversations` | 已实现 |
| 查询单个会话 | `GET /api/v1/agent/conversations/{conversationId}` | 已实现 |
| 写入 Agent 消息 | `POST /api/v1/agent/conversations/{conversationId}/messages` | 已实现 |
| 查询会话消息 | `GET /api/v1/agent/conversations/{conversationId}/messages` | 已实现 |
| 手动记录工具调用 | `POST /api/v1/agent/messages/{messageId}/tool-calls` | 已实现 |
| 查询消息下工具调用 | `GET /api/v1/agent/messages/{messageId}/tool-calls` | 已实现 |
| 查询工具调用列表 | `GET /api/v1/agent/tool-calls` | 已实现 |

### 2.3 当前缺口

- 已新增统一 `AgentOrchestratorService`：`/api/v1/agent/chat` 会经过编排层完成会话落库、LLM 工具规划、工具执行审计和回答生成。
- 还没有 `search_knowledge_base`：百炼知识库 / 项目知识库尚未接入后端工具体系。
- 还没有语义化分析工具：拥堵诊断、异常检测、溢出风险、策略对比等尚未实现。
- `get_decision_trace` 仍偏数据库 trace，没有完整聚合 Traffic-R 原始输出、安全层、fallback 和 CityFlow 执行结果。
- `get_system_health` 仍偏数据库视角，没有主动探测 CityFlow、Traffic-R、WebSocket 和云端连接。
- 应急工具仅有主事件查询，缺少路线节点、绿波状态、ETA 和草案生成。
- 控制建议类工具尚未实现，必须等安全层和仲裁层稳定后再接。

## 3. Agent 工具设计分层

### 3.1 第一层：Agent 可见工具

只暴露语义化工具。它们直接服务用户问题，例如“现在堵在哪里”“为什么这个路口切相位”“救护车路线怎么走”。

这层工具的返回应包含：

- `summary`：一句话结论。
- `evidence`：关键证据列表，必须来自数据库、CityFlow、Traffic-R 日志或知识库。
- `data`：结构化数据，供前端展示或模型继续分析。
- `limitations`：数据缺失、时间滞后、无法判断的边界。
- `recommendations`：建议或下一步，只能是建议，不能默认执行控制动作。

### 3.2 第二层：后端业务服务

例如：

- `SimulationStateQueryService`
- `IntersectionDetailQueryService`
- `ControlDecisionQueryService`
- `TrafficDiagnosisService`
- `EmergencyAgentService`
- `KnowledgeRetrievalService`
- `AgentOrchestratorService`

这一层负责聚合多个底层查询，做业务判断、证据筛选和返回裁剪。

### 3.3 第三层：Repository / SQL / 外部服务

包括：

- `RuntimeQueryService`
- `AgentDataService`
- `DatabaseStatusService`
- CityFlow health / frame / dispatch 调用
- Traffic-R 推理日志查询
- 百炼应用 API 或模型 API 调用

这一层不直接暴露给模型。

## 4. 推荐工具清单

### 4.1 第一批：核心可上线工具

第一批工具用于支撑基础问答、状态查询和决策复盘，是 Agent 可上线的最小闭环。

| 工具名 | 目标 | 输入 | 输出重点 | 状态 |
| --- | --- | --- | --- | --- |
| `get_current_simulation_state` | 查询当前仿真整体状态 | `sid?` | 运行状态、仿真时间、车辆数、平均速度、平均等待、拥堵路段数量、当前策略 | 已实现，需补拥堵路段数量和当前策略汇总 |
| `get_intersection_detail` | 查询路口详情 | `intersectionId`, `sid?`, `sceneCode?` | 当前相位、lane/movement 状态、进口排队、等待时间、关联道路、信号灯状态、附近车辆 | 已实现基础版，需补附近车辆 |
| `get_road_detail` | 查询道路详情 | `roadId`, `sid?`, `sceneCode?` | 车辆数、排队数、平均速度、拥堵等级、上下游路口、车道数量 | 已实现 |
| `get_latest_control_decisions` | 查询最近控制决策 | `sid?`, `intersectionId?`, `limit?` | 策略来源、请求相位、最终相位、持续时间、原因、是否成功下发 | 已实现基础版 |
| `get_decision_trace` | 查询决策链路 | `decisionId` 或后续支持 `sid + intersectionId` | Traffic-R 原始输出、安全校验、fallback、最终执行结果 | 已实现基础版，需增强 |
| `diagnose_congestion` | 分析拥堵原因 | `targetType`, `targetId`, `sid?` | 关键证据、可能原因、影响范围、处理建议 | 未实现，第一优先级 |
| `get_system_health` | 查询系统健康 | `limit?` | Spring Boot、CityFlow、Traffic-R、数据库、WebSocket、云端服务连接状态 | 已实现数据库基础版，需增强 |
| `search_knowledge_base` | 查询项目知识库 | `query`, `topK?`, `scope?` | 命中文档、片段、来源、相似度或引用 | 未实现，第一优先级 |

### 4.2 第二批：应急与区域分析

第二批工具面向应急绿波和区域级状态分析。

| 工具名 | 目标 | 输入 | 输出重点 | 状态 |
| --- | --- | --- | --- | --- |
| `get_emergency_vehicle_status` | 查询应急车辆状态 | `sid`, `vehicleId?`, `eventCode?` | 位置、路线、已通过路口、预计到达时间、绿波状态 | 未实现；可基于 `emergency_event`、`emergency_route_node`、`emergency_signal_event` |
| `draft_emergency_dispatch` | 生成应急调度草案 | `sid`, `start`, `end`, `vehicleType`, `priority?` | 路线、经过路口、预计时间、建议绿波相位；需人工确认 | 未实现；只生成草案，不执行 |
| `get_region_metrics` | 查询区域整体指标 | `sid`, `regionId?`, `intersectionIds?`, `timeRange?` | 平均等待、平均排队、通行量、拥堵指数、平均速度 | 未实现 |
| `detect_spillback_risk` | 检测下游溢出风险 | `sid`, `roadId?`, `intersectionId?`, `regionId?` | 风险等级、下游瓶颈、证据和建议 | 未实现 |
| `detect_signal_anomaly` | 检测信号异常 | `sid`, `intersectionId?`, `timeRange?` | 相位长时间不变、全红/全绿异常、相位映射失败、绿灯车辆不通行 | 未实现 |

### 4.3 第三批：策略评估与报告

第三批工具面向复盘、日报和策略管理。

| 工具名 | 目标 | 输入 | 输出重点 | 状态 |
| --- | --- | --- | --- | --- |
| `compare_strategy_metrics` | 对比策略效果 | `sceneId`, `sessions`, `metrics?` | Fixed-Time、MaxPressure、Traffic-R、Hybrid 的等待、排队、速度、通行量对比 | 未实现；依赖真实实验数据 |
| `draft_signal_adjustment` | 生成信号调整建议 | `sid`, `intersectionId?`, `regionId?`, `objective?` | 建议相位、时长、依据、风险；不执行 | 未实现；依赖安全层和仲裁层 |
| `draft_strategy_switch` | 生成策略切换草案 | `sid`, `regionId?`, `fromStrategy`, `toStrategy` | 切换理由、影响范围、风险和回滚条件 | 未实现；不执行 |
| `generate_daily_operation_report` | 生成运行日报 | `date`, `sceneId?`, `sid?` | 拥堵情况、策略效果、异常事件、模型调用、应急任务 | 未实现 |
| `audit_configuration_consistency` | 检查配置一致性 | `sceneId`, `sid?` | roadnet、相位映射、lane-level、Traffic-R、CityFlow 配置一致性 | 未实现 |
| `recommend_region_partition` | 推荐控制区域划分 | `sceneId`, `criteria?` | Traffic-R core、MaxPressure 边界、复杂控制区建议 | 未实现 |

### 4.4 第四批：实验型工具

实验型工具会消耗资源或创建长任务，必须单独设计审批、异步任务、状态查询和取消机制。

| 工具名 | 目标 | 输入 | 输出重点 | 状态 |
| --- | --- | --- | --- | --- |
| `run_shadow_simulation_compare` | 发起影子仿真或对比实验 | `sceneId`, `flowId`, `strategies`, `duration`, `seed?` | 实验任务 ID、预计耗时、状态查询入口 | 未实现；后置 |

## 5. 不建议直接暴露给 Agent 的底层工具

以下能力可以保留为后端内部接口或运维查询，但不建议直接交给模型频繁调用：

| 底层能力 | 建议处理 |
| --- | --- |
| `get_safety_events` / `get_safety_constraint_log` | 作为 `get_decision_trace`、`diagnose_congestion`、`detect_signal_anomaly` 的内部证据 |
| `get_fallback_events` / `get_fallback_log` | 作为 `get_decision_trace`、`get_system_health`、`diagnose_congestion` 的内部证据 |
| `get_alert_events` | 并入 `get_system_health` 或 `diagnose_congestion` |
| `get_emergency_events` | 并入 `get_emergency_vehicle_status` |
| `get_model_inference_log` | 可保留为调试工具，但普通问答优先通过 `get_decision_trace` 间接使用 |
| `explain_algorithm_concept` | 不单独做数据库工具，优先走 `search_knowledge_base + 模型回答` |

## 6. Agent 编排设计

已新增 `AgentOrchestratorService`，作为 `/api/v1/agent/chat` 的新核心。

推荐流程：

```text
用户问题
  -> 创建或读取 agent_conversation
  -> 写入 user agent_message
  -> 意图识别
  -> 选择工具：
       实时状态类 -> runtime / diagnosis tools
       文档规范类 -> search_knowledge_base
       综合问题 -> 数据库工具 + 知识库
       控制建议类 -> draft_*，只生成草案
  -> 写入 agent_tool_call
  -> 组装模型上下文
  -> 调用百炼模型 API 或绑定知识库的百炼应用 API
  -> 写入 assistant agent_message
  -> 返回答案、引用证据和工具调用摘要
```

### 6.0 当前阶段 2 落地状态

当前阶段 2 已完成后端编排骨架：

- `AgentController` 不再直接调用模型，而是调用 `AgentOrchestratorService`。
- `AgentOrchestratorService` 负责创建或读取 `agent_conversation`，写入 user / assistant `agent_message`。
- `AgentIntentClassifier` 使用 LLM 生成结构化 JSON 工具规划，不用后端硬编码规则选择工具。
- `AgentToolExecutor` 负责后端工具白名单过滤、参数校验、调用 `RuntimeQueryService`，并把每个工具调用写入 `agent_tool_call`。
- `AgentResponseAssembler` 将 LLM 规划、工具结果和上下文组装给模型生成最终回答。
- LLM 规划轨迹会以 `llm_tool_plan` 写入 `agent_tool_call`，接口响应中也返回 `planTrace`。

当前工具规划不是让模型直接执行 Java 方法，而是采用“LLM 输出 JSON 计划 -> 后端白名单执行”的安全模式。这样既满足由 LLM 进行工具调用决策，又避免模型越权调用未开放能力。

当前仍未完成：

- `search_knowledge_base` 尚未接入，所以规范/部署/算法说明类问题仍只能由模型基于已有上下文回答。
- `diagnose_congestion`、`detect_signal_anomaly` 等语义化分析工具尚未实现。
- 暂未开放执行类工具；策略切换、相位下发、应急绿波执行仍必须等待安全层和仲裁层。

### 6.1 意图分类

最小分类：

| 意图 | 示例 | 推荐工具 |
| --- | --- | --- |
| 当前状态查询 | “现在仿真跑到哪里了？” | `get_current_simulation_state` |
| 路口/道路详情 | “这个路口为什么堵？” | `get_intersection_detail`、`diagnose_congestion` |
| 决策复盘 | “刚才为什么切成 NTST？” | `get_latest_control_decisions`、`get_decision_trace` |
| 系统故障 | “为什么没推理结果？” | `get_system_health`、`get_model_inference_log` |
| 应急调度 | “从 A 到 B 救护车怎么走？” | `draft_emergency_dispatch` |
| 知识问答 | “MaxPressure 是什么？” | `search_knowledge_base` |
| 报告生成 | “生成今天运行日报” | `generate_daily_operation_report` |

### 6.2 百炼调用方式

本项目不依赖百炼平台 Agent 做工具调用。推荐两种百炼接入方式：

1. 百炼模型 API：用于最终自然语言生成。
2. 绑定知识库的百炼应用 API：用于项目文档、交通规范、算法说明和部署资料的知识库问答。

实时交通数据不得放入百炼知识库，必须从本项目数据库和 CityFlow 状态查询。

### 6.2.1 LangChain4j 接入状态

当前已完成第一阶段依赖与配置准备，并在第二阶段接入编排层：

- 后端保持 Spring Boot `3.3.5`，不升级 Spring Boot。
- 已在 `backend/pom.xml` 引入 `dev.langchain4j:langchain4j` 与 `dev.langchain4j:langchain4j-open-ai`。
- 暂不引入 `langchain4j-spring-boot-starter`，避免引入 Spring Boot 版本升级风险。
- 已在 `application.yml` 增加 `traffic.agent.langchain4j.*` 配置。
- `traffic.agent.langchain4j.enabled` 默认是 `false`，未开启时编排层会复用现有 `BailianAgentService` 调用百炼。
- 开启 `traffic.agent.langchain4j.enabled=true` 后，会通过普通 Java API 创建 LangChain4j `ChatModel`。
- 第二阶段采用 LLM JSON 规划模式，不依赖 `langchain4j-spring-boot-starter`。

当前配置项：

```yaml
traffic:
  agent:
    langchain4j:
      enabled: false
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key:
      model-name: qwen-plus
      temperature: 0.2
      timeout-seconds: 60
```

后续实现规则：

- 不允许模型直接访问 SQL。
- 不允许 Agent 工具直接调用 Controller。
- LangChain4j 工具应调用 `RuntimeQueryService`、`AgentDataService` 和后续分析服务。
- 控制类能力只允许生成草案，不允许直接下发相位或切换策略。

### 6.3 知识库内容范围

`search_knowledge_base` 应覆盖：

- 项目文档；
- API 说明；
- 数据库结构说明；
- 部署手册；
- 风险 TODO；
- Traffic-R / MaxPressure / Fixed-Time 算法说明；
- 应急绿波、安全约束、仲裁层规则；
- 团队常见问题。

不应覆盖：

- 高频实时仿真帧；
- 原始车辆轨迹大数据；
- API Key、数据库密码、云端令牌；
- 未脱敏的个人信息。

## 7. 返回格式规范

所有 Agent 可见工具建议统一返回：

```json
{
  "summary": "一句话结论",
  "target": {
    "type": "intersection | road | region | session | system | knowledge",
    "id": "目标ID"
  },
  "evidence": [
    {
      "source": "database | cityflow | traffic-r | knowledge-base | health-check",
      "name": "证据名称",
      "value": "证据值",
      "time": "可选时间"
    }
  ],
  "data": {},
  "limitations": [],
  "recommendations": []
}
```

建议类工具额外返回：

```json
{
  "draftOnly": true,
  "requiresHumanConfirmation": true,
  "safetyChecksRequired": [
    "phase_validity",
    "min_green",
    "conflict_movement",
    "spillback"
  ]
}
```

## 8. 权限与安全边界

- 查询工具只读，不改变仿真状态。
- 分析工具只生成诊断，不下发控制动作。
- 草案工具只生成候选建议，不执行。
- 执行类工具暂不开放给 Agent；后续如开放，必须经过安全层、仲裁层和人工确认。
- `run_shadow_simulation_compare` 属于资源消耗型工具，必须异步化并支持取消。
- 工具调用结果入库前必须截断大 payload，不能保存 API Key、认证头或云端密码。

## 9. 分阶段实施计划

### 阶段 A：整理现有工具边界

目标：

- 保留当前已实现查询工具；
- 明确哪些工具只作为内部证据；
- 在 `API_GUIDELINES.md` 中标注 Agent 可见工具和内部辅助接口；
- 为 `get_decision_trace` 增加 `sid + intersectionId` 查询能力。

验收：

- 当前 11 个工具接口可用；
- `agent_tool_call` 能记录成功和失败工具调用；
- 文档和接口一致。

### 阶段 B：实现知识库接入

目标：

- 实现 `search_knowledge_base`；
- 支持调用绑定知识库的百炼应用 API，或先以本地文档检索替代；
- 工具返回引用文档、片段和来源；
- 不把实时状态写入知识库。

验收：

- 能回答项目文档、接口、部署、算法概念问题；
- 返回引用来源；
- 不依赖百炼平台 Agent 工具调用。

### 阶段 C：实现核心分析工具

目标：

- 实现 `diagnose_congestion`；
- 增强 `get_decision_trace`；
- 增强 `get_system_health`；
- 实现 `get_emergency_vehicle_status`。

验收：

- 拥堵诊断能引用路口、道路、决策、fallback、安全事件证据；
- 系统健康能覆盖 Spring Boot、CityFlow、Traffic-R、数据库；
- 决策追踪能解释“模型建议 -> 安全/仲裁 -> 下发结果”。

### 阶段 D：实现应急与区域工具

目标：

- 实现 `draft_emergency_dispatch`；
- 实现 `get_region_metrics`；
- 实现 `detect_spillback_risk`；
- 实现 `detect_signal_anomaly`。

验收：

- 应急工具只生成草案，不执行；
- 区域指标来自真实 session 数据；
- 异常检测有明确阈值和证据。

### 阶段 E：实现报告和策略草案

目标：

- 实现 `compare_strategy_metrics`；
- 实现 `draft_signal_adjustment`；
- 实现 `draft_strategy_switch`；
- 实现 `generate_daily_operation_report`；
- 实现 `audit_configuration_consistency`；
- 实现 `recommend_region_partition`。

验收：

- 策略对比只使用可复现实验数据；
- 所有建议工具均标记 `draftOnly=true`；
- 报告可以引用数据库记录和知识库资料。

### 阶段 F：实验型工具

目标：

- 设计并实现 `run_shadow_simulation_compare`。

前置条件：

- 实验任务表；
- 异步任务执行器；
- 任务状态查询；
- 取消机制；
- 资源配额；
- 人工确认。

## 10. 当前优先级

下一步优先实现：

1. `search_knowledge_base`
2. `diagnose_congestion`
3. 增强 `get_decision_trace`
4. 增强 `get_system_health`
5. `get_emergency_vehicle_status`

暂缓：

- `run_shadow_simulation_compare`
- 直接执行控制动作的工具
- 未经过安全层和仲裁层的策略切换工具

## 11. 代码落地建议

建议新增或调整以下包：

```text
com.traffic.agent.orchestrator
com.traffic.agent.tool
com.traffic.agent.knowledge
com.traffic.agent.analysis
com.traffic.agent.report
```

职责建议：

| 包 | 职责 |
| --- | --- |
| `agent.orchestrator` | 用户问题入口、意图识别、工具选择、模型调用、答案组装 |
| `agent.tool` | Agent 可见工具注册、参数 schema、调用审计 |
| `agent.knowledge` | 百炼知识库 / 本地文档知识库检索 |
| `agent.analysis` | 拥堵诊断、异常检测、溢出风险、策略对比 |
| `agent.report` | 日报、运行报告、策略复盘报告 |

现有 `runtime.query` 继续作为只读数据底座，不直接承载复杂智能分析。

## 12. 与现有任务台账关系

- TASK-20260711-06：Agent 核心工具调用，负责当前核心查询工具和审计闭环。
- TASK-20260711-12：Agent 增强分析能力，负责诊断、异常检测、策略解释和报告生成。
- TASK-20260711-13：运维与部署完善，负责系统健康探测和服务状态记录。

后续开发时，以本文档作为 Agent 工具范围和分阶段实现依据；接口字段落地后同步更新 `API_GUIDELINES.md`，数据库访问变化同步更新 `DATABASE_STRUCTURE.md`。
