# 公共工作台账

本文件是项目当前未完成开发工作的唯一公共台账。任务来源为团队前期调研，并结合 2026-07-11 当前代码状态补充“已有基础与剩余缺口”。

详细接口、设计、部署和风险说明保存在对应权威文档中；本文件只维护任务边界、状态、依赖和可验证的交付结果。维护规则见根目录 `agent.md`。

## 维护规则

- 状态只使用：`待开始`、`进行中`、`阻塞`、`待验证`。
- “已有部分代码”不代表任务正在执行；只有明确负责人已经开始本轮工作时才改为 `进行中`。
- 任务开始前补充负责人、实施计划和预计影响文件。
- 任务完成后先把长期有效的信息同步到权威文档，再从本台账删除；历史由 Git 提交和 PR 保存。
- 技术风险只引用 `backend/docs/RISK_TODO.md` 中的编号，不在本文件复制风险全文。
- 一个任务需要多人协作时仍保留一个主任务编号，使用负责人和 PR 拆分实施，不重复建立同目标任务。
- 依赖关系中的 `TASK-01` 至 `TASK-13` 是当日完整编号 `TASK-20260711-01` 至 `TASK-20260711-13` 的简称。

## P0 核心闭环

P0 是进入可信策略实验和真实系统演示前必须完成的基础能力。

| 编号 | 状态 | 负责人 | 工作模块 | 目标与具体任务 | 当前基础与剩余缺口 | 交付与验收标准 | 依赖/关联 |
|---|---|---|---|---|---|---|---|
| TASK-20260711-01 | 待开始 | 待领取 | 控制优先级与调度仲裁 | 建立统一控制仲裁层。安全约束优先级最高，其次应急绿波，再次 Traffic-R、MaxPressure、Fixed-Time。所有策略只生成候选建议，只有仲裁层可以形成最终执行决策并下发 CityFlow。 | 已有统一 `ControlDecision`、`StrategyDispatchService` 和应急 override，但普通策略与应急控制分别生效，尚无单一仲裁入口、明确优先级和冲突追踪。 | 所有控制动作经过同一仲裁流程；可追踪候选、驳回原因和最终决策；形成控制优先级文档及策略冲突规则；覆盖 RL、fallback 与应急并发测试。 | 依赖 TASK-02；影响 `API_GUIDELINES.md`、`CALL_CHAIN.md`、`BACKEND_ARCHITECTURE.md`。 |
| TASK-20260711-02 | 待开始 | 待领取 | 安全约束层 | 实现相位合法性、冲突 movement、最小/最大绿灯、黄灯与全红过渡、下游溢出、应急优先边界等规则。任何策略和人工操作都不能绕过安全层。 | 当前主要校验相位编号和模型输出，缺少独立 Safety Layer、时序状态机、冲突 movement 校验和安全 fallback 证据链。 | 提供独立 Safety Layer；非法建议不会下发；安全校验和 fallback 有结构化日志；每条规则具备单元测试和至少一个拒绝案例。 | TASK-01 的强制前置；依赖真实相位与 movement 数据，关联 RISK-016。 |
| TASK-20260711-03 | 待开始 | 待领取 | CityFlow 仿真可信度修复 | 前端信号灯、车辆位置、道路状态和指标严格由 CityFlow 帧驱动；禁止前端定时器或模型响应自行推断真实状态；后端只推送仿真快照、候选/最终决策事件。 | 正式前端已接收 `sim.frame`，后端也以 CityFlow 信号状态为准；但大屏仍混用数据库种子、随机 mock 更新和本地 AI/应急操作，路口内车辆轨迹仍为近似。 | 真实模式下不存在 mock 覆盖；信号显示、车辆行为和最终执行相位可逐帧对应；断线或无真帧时明确降级，不能继续伪造实时状态；完成端到端一致性测试。 | 与 TASK-08 联动；关联 RISK-009、RISK-024、RISK-026。 |
| TASK-20260711-04 | 待开始 | 待领取 | lane-level 状态与 Traffic-R 输入对齐 | 输出每条 lane 的车辆数、排队数、等待时间、平均速度、方向和 turn movement；建立 CityFlow 到 Spring Boot 到 Traffic-R 的唯一字段规范，并严格对齐官方评测输入。 | 已有按路口组织的 `WT/WL/ST/SL/ET/EL/NT/NL`、queue、wait、cells 输入和 `/predict-batch` 日志；仍缺完整原始 lane 标识、平均速度、movement 映射和端到端字段完整性验证。 | 发布 Traffic-R 输入数据规范和 lane-level 接口；模型请求/返回可按 `sid`、仿真时间、路口追踪；使用真实场景样本完成契约测试和官方格式对照。 | 为 TASK-01、TASK-02、TASK-09、TASK-11 提供数据；关联 RISK-025。 |
| TASK-20260711-05 | 进行中 | 后端组 / Codex | 数据库落库（8 阶段） | 建立并使用路网、路口、道路、车道、仿真会话、仿真帧、lane 状态、控制决策、模型推理、安全日志和 fallback 日志等核心表。分阶段接入 Repository/Service：①路网目录同步 ②仿真会话生命周期 ③帧快照采样 ④控制决策轨迹 ⑤Traffic-R/fallback/安全日志 ⑥应急事件 ⑦Agent 查询层 ⑧数据分析替换。 | 已新增 `V5__runtime_persistence_support.sql`，补充运行时间、执行状态、movement lane 快照、Traffic-R 批量逐路口结果和查询索引。本轮继续完成第 8 阶段：新增仿真指标采样表和会话生命周期写入，数据分析页保留未优化历史基线并持续读取数据库遥测，路网大屏仅在策略切换后平滑展示优化效果。剩余缺口：应急事件、安全约束事件、失败 Traffic-R 请求完整审计。 | 增量迁移在 H2 和 PostgreSQL 测试环境可重复验证；核心运行数据真实落库；固定配时初始值保持合理拥堵基线；切换 Max-Pressure/Traffic-R 后排队、等待、速度和拥堵指标随持久化仿真采样逐步改善，页面不在历史/实时两套数据间跳变。 | 为 TASK-06、TASK-11、TASK-12 提供数据；接口变化同步 `backend/docs/API_GUIDELINES.md`，结构变化同步 `backend/docs/DATABASE_STRUCTURE.md`；后续继续补齐应急/安全/失败审计。 |
| TASK-20260711-06 | 待验证 | 后端组 | Agent 核心工具调用 | 实现 `get_current_simulation_state`、`get_intersection_detail`、`get_road_detail`、`get_latest_control_decisions`、`get_decision_trace`、`get_system_health`、`get_model_inference_log`。Agent 必须通过受控工具查询真实数据，不根据提示上下文猜测。 | 后端已提供与工具名对应的 HTTP 查询入口：`/api/v1/agent/tools/get_current_simulation_state`、`get_intersection_detail/{intersectionId}`、`get_road_detail/{roadId}`、`get_latest_control_decisions`、`get_decision_trace/{decisionId}`、`get_system_health`、`get_model_inference_log`，并补充 `get_fallback_events`、`get_safety_events`、`get_alert_events`、`get_emergency_events`。已新增 `AgentDataService` 与 `/api/v1/agent/conversations`、`/messages`、`/tool-calls` 接口，支持会话、消息和工具调用审计；工具接口传入 `messageId` 时会写入 `agent_tool_call`。剩余缺口：百炼 MCP 平台工具注册、鉴权边界、云端真实数据验收。 | 形成 Agent 工具接口和参数规范；工具返回真实后端数据；具备超时、错误、权限和审计处理；完成基础状态问答与决策追踪场景测试。 | 依赖 TASK-05；健康工具依赖 TASK-13；为 TASK-12 前置。 |

## P1 功能与评测

P1 在 P0 数据、安全和仲裁边界稳定后推进，避免在不可信底座上扩展界面或实验。

| 编号 | 状态 | 负责人 | 工作模块 | 目标与具体任务 | 当前基础与剩余缺口 | 交付与验收标准 | 依赖/关联 |
|---|---|---|---|---|---|---|---|
| TASK-20260711-07 | 待开始 | 待领取 | 地图路口与 CityFlow 路口绑定 | 保存 `mapIntersectionId` 与 `cityflowIntersectionId` 映射；点击地图路口进入 `/map/intersections/{intersectionId}`；展示相位、lane 状态、附近车辆以及 roadLink/laneLink 几何。 | 已有地图路口选择、CityFlow 路口 DTO 和数据库路口访问接口，但缺少稳定 ID 映射、详情路由和真实详情聚合接口。 | 提供绑定配置或数据库表、路口详情 REST 接口和前端详情页；点击、刷新、无映射和数据缺失场景均有确定行为。 | 依赖 TASK-04、TASK-05；接口变化更新 `API_GUIDELINES.md`。 |
| TASK-20260711-08 | 待开始 | 待领取 | 真实道路与转弯轨迹增强 | 道路按 `road.points` 渲染折线或曲线；车辆进入路口后按 `laneLinks[].points` 插值；平滑车辆角度，区分左转、右转和直行轨迹。 | 道路已支持 points，车辆具备 lane 横向偏移和跨 road 跳变缓解；CityFlow 帧尚未提供可直接驱动路口内部运动的 laneLink 进度，转弯仍近似。 | 完成曲线道路和 laneLink 轨迹渲染；车辆无穿越、飞车和突变朝向；使用真实 CityFlow 场景录制或自动化轨迹检查验证。 | 依赖 TASK-03；关联 RISK-009。 |
| TASK-20260711-09 | 待开始 | 待领取 | 大路网混合控制策略 | 识别标准四进口路口作为 Traffic-R core；复杂与边界路口使用 MaxPressure；低流量外围使用 Fixed-Time；策略负责连续区域，并在边界执行 spillback protection。 | 已有三类控制器和 Traffic-R fallback，但当前会话通常使用单一 controller，尚无路口分类、连续区域划分和跨区域边界协调。 | 形成区域划分规则、Hybrid 调度方案和可配置策略映射；同一时刻每个路口只有一个有效策略来源；边界溢出保护有测试和指标。 | 依赖 TASK-01、TASK-02、TASK-04。 |
| TASK-20260711-10 | 待开始 | 待领取 | 应急绿波调度 | 将地图起终点吸附到 CityFlow roadnet；基于道路图规划路径；动态插入或标记应急车辆；生成沿途绿波候选请求，并统一经过安全层和仲裁层。 | Python 已有路径规划、EV 注入/识别、优先相位和 override 释放；Spring Boot 有 dispatch 接口；正式前端仍以本地模拟为主，且应急动作尚未经过统一安全仲裁。 | 提供真实路线规划、EV 状态和绿波任务接口；前端可选起终点并观察真实 EV；多车冲突、任务结束和恢复普通策略均可验证。 | 依赖 TASK-01、TASK-02、TASK-07；关联 RISK-030。 |
| TASK-20260711-11 | 待开始 | 待领取 | 策略效果对比 | 在相同 roadnet、flow、随机种子和仿真参数下运行 Fixed-Time、MaxPressure、Traffic-R、Hybrid；比较等待时间、排队长度、通行量、平均速度和拥堵指标。 | 前端已有演示对比图，Traffic-R 有接口测试和实时调度；尚无可重复离线 evaluator、统一实验元数据和可信结果数据源。 | 生成可复现实验配置与结果；保存原始指标和汇总；前端只展示真实实验数据；日报和报告可引用同一数据源。 | 依赖 TASK-04、TASK-05、TASK-09；关联 RISK-023。 |

## P2 智能分析与运维

| 编号 | 状态 | 负责人 | 工作模块 | 目标与具体任务 | 当前基础与剩余缺口 | 交付与验收标准 | 依赖/关联 |
|---|---|---|---|---|---|---|---|
| TASK-20260711-12 | 待开始 | 待领取 | Agent 增强分析能力 | 增加 `diagnose_congestion`、`detect_signal_anomaly`、`detect_spillback_risk`、`compare_strategy_metrics`、`draft_emergency_dispatch`、`generate_daily_operation_report` 等分析工具。 | 已有交通领域系统提示词和聊天界面，但分析结论主要由模型根据摘要生成，缺少基于工具证据的诊断、引用和可审计输出。 | 支持拥堵诊断、异常分析、策略解释和日报生成；每条结论能追溯到工具结果或数据库记录；建议类工具默认不直接执行控制动作。 | 依赖 TASK-06、TASK-11；应急草案遵守 TASK-01、TASK-02。 |
| TASK-20260711-13 | 待开始 | 待领取 | 运维与部署完善 | 保证 CityFlow 云端稳定运行、Traffic-R 按需启动；增加 Spring Boot 统一健康检查、服务状态记录、异常告警，并整理部署、重启和排障流程。 | CityFlow 已有云端 24h 运行手册，Traffic-R 有按需启动和隧道说明，两个 Python 服务均有 `/health`；Spring Boot 尚未形成统一主动检查、状态历史和异常告警。 | 提供统一服务健康接口和状态记录；关键异常可告警；云端部署、重启、版本核对和常见故障均有可执行手册；完成一次故障演练。 | 关联 RISK-004；更新 `DEPLOYMENT.md` 和对应 runbook。 |

## 建议执行顺序

1. **可信输入与安全基础**：TASK-04 lane-level 对齐、TASK-02 安全约束、TASK-03 CityFlow 状态一致性。
2. **统一控制出口**：TASK-01 控制仲裁。完成前不继续增加可直接下发信号的新入口。
3. **可追溯数据底座**：TASK-05 数据库落库、TASK-13 健康与运维。
4. **核心能力接入**：TASK-06 Agent 核心工具、TASK-07 地图绑定、TASK-08 轨迹增强。
5. **复杂调度与业务闭环**：TASK-09 Hybrid、TASK-10 应急绿波。
6. **可信评测与分析**：TASK-11 策略对比、TASK-12 Agent 增强分析。

允许并行的工作：TASK-03 与 TASK-04；TASK-05 与 TASK-13；TASK-07 与 TASK-08。涉及控制动作的 TASK-09、TASK-10 必须等待 TASK-01 和 TASK-02 的接口稳定。

## 当前阻塞

暂无已确认的独立阻塞项。任务发生阻塞时，在此记录任务编号、阻塞原因、已尝试方案、解除条件和需要的协作者。

## 新任务模板

新增工作前先确认它不能归入以上任务。确需新增时，在对应优先级表中添加：

```text
| TASK-YYYYMMDD-NN | 待开始 | 姓名或待领取 | 模块 | 目标和具体任务 | 已有基础与缺口 | 可验证的交付标准 | 依赖和权威文档 |
```
