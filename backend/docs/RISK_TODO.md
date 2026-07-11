# 后端风险与 TODO 清单

本文档用于记录后端、Python 仿真服务、接口调用链中已经发现但尚未完全解决的半成品、不完善实现、潜在漏洞和技术债。

## 维护规则

1. 发现半成品、不完善、有漏洞或可能误导团队的代码时，必须记录到本文档。
2. 不能因为“暂时能跑”就放任问题积累。
3. 每条记录必须写清楚影响范围、风险等级、当前状态和后续处理方式。
4. 如果问题会影响当天联调或验收演示，必须优先修复；如果暂时不修，也要明确原因。
5. 修复后不要直接删除记录，应先改为“已解决”，并写明对应提交或修改位置。

## 风险等级

| 等级 | 含义 |
|---|---|
| P0 | 会阻断系统启动、核心链路联调或最终验收 |
| P1 | 会导致数据错误、接口不兼容、演示效果明显异常 |
| P2 | 会影响后续扩展、维护成本或局部功能稳定性 |
| P3 | 代码风格、文档缺口、低风险优化项 |

## 当前记录

| 编号 | 等级 | 模块 | 问题 | 影响 | 状态 | 后续处理 |
|---|---|---|---|---|---|---|
| RISK-001 | P1 | sim-python | 默认 `SIM_ENGINE_MODE=mock` 只生成可视化帧；真实 CityFlow 需要显式切换到 `SIM_ENGINE_MODE=cityflow` | 如果运行环境没有切换，会把 mock 输出误当成真实仿真 | 已处理 | 已实现真实 CityFlow 模式；运行时必须检查 `/health.engineMode` |
| RISK-002 | P2 | sim-python | 仿真会话仍保存在内存中，服务重启后丢失 | 不影响今天可视化联调，但不适合长期历史记录 | 已记录 | 后续由 Spring Boot 负责会话和指标持久化，Python 只保留运行态 |
| RISK-003 | P2 | sim-python | 当前 HTTP 服务使用 Python 标准库 `ThreadingHTTPServer` | 可用于最小联调，但缺少生产级中间件、日志、超时和结构化异常能力 | 已记录 | 若后续接口增多，迁移到 FastAPI 或 Flask，并保持接口路径不变 |
| RISK-004 | P1 | backend/sim-python | Spring Boot 当前未主动调用 `/health` 检查 Python 服务状态 | Python 未启动或模式错误时，错误只会在业务调用时暴露 | 待处理 | 后续在 Spring Boot 增加 CityFlow 健康检查接口或启动前检查 |
| RISK-005 | P2 | sim-python | roadnet/flow 示例数据直接放在仓库中 | 数据规模变大后会导致仓库膨胀 | 已记录 | 当前保留小样例用于联调；大数据集后续改为外部数据目录或 Git LFS |
| RISK-006 | P1 | sim-python/CityFlow | CityFlow 仅在 WSL Ubuntu 的 conda 环境 `traffic-rl` 中可 import，Windows/base Python 无法 import | 真实 CityFlow 服务必须在 WSL `traffic-rl` 环境启动，Windows 侧不能直接运行真实引擎 | 已记录 | 后续启动命令必须显式使用 `conda activate traffic-rl`，Spring Boot 通过 HTTP 调用 WSL 内服务 |
| RISK-007 | P1 | sim-python/CityFlow | `/home/huangchengjun666/CityFlow/examples/config.json` 使用 `"dir": "./"`，从错误目录运行会加载失败 | 虽然 `cityflow.Engine` 对象能创建并 `next_step` 不报错，但真实 roadnet 可能未加载 | 已处理 | 已用 `config_abs.json` 验证真实 CityFlow 可返回车辆和车道统计；服务内改为按场景生成绝对路径 config |
| RISK-008 | P2 | sim-python/CityFlow | CityFlow 0.1 没有 `get_tl_phase` 方法 | Python 无法直接从 Engine 读取当前信号相位，后续策略控制时可能出现相位状态不同步 | 已缓解 | 当前真实引擎默认由 Spring Boot 策略下发相位并由 Python session 记录当前相位；如需恢复 Python 固定周期演示，可显式设置 `SIM_AUTO_SIGNAL_CYCLE=true` |
| RISK-009 | P2 | sim-python/CityFlow | 真实 CityFlow 车辆位置由 lane + distance 近似映射到 road polyline，当前 Python frame 未返回 laneLink 曲线或车辆在路口内部的精确坐标 | 前端已按 lane 做横向偏移，并在跨 road 跳变时禁用长距离直线插值，能缓解车道重叠和飞车；但车辆真实转弯轨迹仍是近似效果 | 已缓解 | 后续扩展 roadnet/frame 协议，返回 laneLink points 或更精确的车辆坐标，用于真实转弯动画 |
| RISK-010 | P2 | sim-python/CityFlow | Windows Codex 环境无法直接访问用户 WSL `traffic-rl` 环境 | 本地自动化测试只能覆盖 mock 模式和代码编译，真实 CityFlow 模式需要在用户 WSL 中手动验证 | 已记录 | 每次修改真实模式后，必须在 WSL `traffic-rl` 中运行启动和 frame 接口验证 |
| RISK-011 | P2 | backend | Windows 中文用户目录会导致 Spring Boot Maven 插件生成的 argfile/classpath 路径转码异常，表现为 `mvn spring-boot:run` 报 `NoClassDefFoundError: org/springframework/boot/SpringApplication` | 团队成员如果直接使用默认用户目录下的 Maven 缓存和临时目录，可能误以为后端不可用，影响联调效率 | 已处理 | 已新增 `backend/.mvn/maven.config`，将 Maven 本地仓库和 Java 临时目录固定到模块内 ASCII 路径；启动时必须先 `cd backend` 再执行 `mvn spring-boot:run` |
| RISK-012 | P1 | deployment | 当前本地真实 CityFlow 依赖 WSL `traffic-rl` 环境，云端部署不能直接照搬 WSL 启动方式 | 如果云端仍按本地 WSL/localhost 思路部署，Spring Boot 可能无法连接 Python CityFlow，导致仿真链路中断 | 已记录 | 云端必须将 Python CityFlow 作为独立 Linux 服务或容器部署，并按 `DEPLOYMENT.md` 配置 `cityflow.base-url` |
| RISK-013 | P3 | local-dev | 本地开发如果每次手动进入 WSL 并激活 `traffic-rl`，容易漏步骤或启动在错误目录 | 会降低联调效率，或误启动 mock/base 环境导致 Spring Boot 连接到错误服务 | 已处理 | 已新增 `scripts/start-cityflow-wsl.ps1` 和 `scripts/stop-cityflow-wsl.ps1`，本地开发优先使用脚本 |
| RISK-014 | P2 | backend/frontend | 为提升可视化流畅度，Spring Boot 默认以 `cityflow.frame-poll-interval-ms=100` 轮询 Python frame 接口，真实 CityFlow config `interval` 保持 0.2 秒 | 画面更顺滑且仿真时间不快进；但会提高 Python CityFlow 服务调用频率，低性能机器或多人联调时可能增加 CPU 压力 | 已记录 | 演示优先使用 100ms；如果本机卡顿或 Python 服务压力过高，可在 `application.yml` 中调大轮询间隔 |
| RISK-015 | P2 | backend/strategy | Spring Boot 已通过 `StrategyDispatchService` 统一生成 Fixed-Time、Max-Pressure、Traffic-R 决策，并异步提交 Python `/cityflow/simulations/{sid}/actions`；当前流程是先取 frame 再下发 actions | 信号控制效果通常从后续 frame 体现，`control.decision` 只表示动作已生成并提交，不能代表当前帧真实信号灯 | 已处理 | 已新增 Python actions 接口、Spring Boot `CityFlowClient.applyControlActions` 和 `control.decision` WebSocket 消息；前端真实灯色必须以 `sim.frame.data.signals` 为准 |
| RISK-016 | P2 | backend/strategy | `StrategyDispatchService` 当前使用 Jinan 1..9 相位占位候选，未从 roadnet 的真实 `phases[].roadLinkIndexes` 构造每个路口的完整候选集 | Fixed-Time 可运行，但 Max-Pressure 和 Traffic-R 的相位解释、roadLink 级压力计算仍不精确 | 待处理 | 后续在会话创建时缓存 `RoadnetResponse.phases`，按 `intersectionId` 生成真实 `PhaseCandidate` |
| RISK-017 | P1 | backend/strategy/traffic-r | `RlController` 曾为 placeholder，尚未读取配置并调用云端 Traffic-R | 创建 `controllerType=traffic-r` 的仿真会话时，后端会保持当前相位，不会真正使用云端模型调度 CityFlow | 已处理 | 已新增 `TrafficRProperties`、`CloudTrafficRClient`、请求/响应 DTO；正式调度通过 Traffic-R `/predict-batch` 批量响应转换为统一 `ControlDecision` |
| RISK-018 | P1 | backend/strategy/traffic-r | Traffic-R 在线推理已改为会话级 `/predict-batch` 异步调度，并要求 `parsedFromModel=true` 与非空 `rawOutput` | 隧道断开、云端 422、空输出或非法相位会导致 RL 决策无效 | 已处理 | 已实现连续 3 次无效后自动启用 Max-Pressure 整帧 fallback，fallback 期间继续探测 Traffic-R，连续 3 次有效后恢复应用 RL 决策 |
| RISK-019 | P1 | backend/sim-python | 用户运行中的 Python CityFlow 服务可能不是最新代码，表现为 `/cityflow/simulations/{sid}/actions` 返回 404 `endpoint not found` | 策略决策能生成但无法真正下发给 CityFlow，信号灯行为不会被 RL/Max-Pressure/Fixed-Time 控制；旧后端逻辑还会阻断前端帧动画 | 已缓解 | 后端已隔离策略生成和 actions 下发异常，保证 `sim.frame` 继续推送；真实控制效果必须重启最新 `sim-python` 服务，并验证 actions 接口存在 |
| RISK-020 | P1 | cloud/traffic-r | 云端 Traffic-R 服务必须使用 `/predict-batch`，如果仍启动旧版单路口 `/predict` 服务，Spring Boot 的 RL 调度会调用失败 | 前端动画仍可继续，但不会收到 RL 批量决策，无法验证所有路口策略控制效果 | 已记录 | AutoDL 上需替换为 `cloud/traffic-r/traffic_r_service.py` 或等价批量服务，并验证 `/health.supportsBatch=true` 与 `POST /predict-batch` |
| RISK-021 | P2 | temp-three-frontend/sim-python | 临时前端默认使用 `5x` 仿真倍率以便快速压测拥堵 | 高倍率会让真实 CityFlow 每帧推进多步，可能增加 Python CPU 压力，也会让车辆跳变更明显 | 已记录 | 演示压测可用 `5x` 或 `8x`；如果画面跳变或 Python 压力过高，切回 `1x` 或 `3x` 重建会话 |
| RISK-022 | P2 | sim-python/data | 为快速观察 RL 调度效果，新增 `jinan_3x4_stress` 高流量场景，flow 需求约为原始 Jinan 的 3 倍且发车时间轴压缩到约 450 秒 | 压测场景更容易出现拥堵，但不能直接等同于原始数据集评测结果 | 已记录 | 实验汇报时需区分 `jinan_3x4` 正常场景和 `jinan_3x4_stress` 压测场景；最终指标对比仍应保留原始 flow 结果 |
| RISK-023 | P1 | strategy/visualization | LLMTSCS 官方评测是 Python 内部批量决策后一次推进一个控制周期；当前系统是实时可视化链路，模型推理和仿真推进异步进行 | 直接观察前端动画时，RL 效果会比离线评测更滞后，不能把等待时间误判为模型无效 | 已记录 | 演示使用 stress flow、20x 以上倍率和 warmup；正式指标评测后续应增加离线 evaluator，而不是只依赖前端实时动画 |
| RISK-024 | P1 | frontend/signal | Jinan 原始 CityFlow phase 的 `availableRoadLinks` 可能包含多个转向连接，直接按 roadLink 全量渲染会出现近似全绿的视觉误导 | 前端可能显示不符合业务直觉的全绿信号，误判为云端模型返回错误 | 已缓解 | 前端信号灯灯头已改为按业务 `phaseCode` 的东西/南北主方向显示；CityFlow 底层仍使用 1..4 相位下发，后续需进一步校准 Traffic-R action 与官方环境 phase 映射 |
| RISK-025 | P1 | backend/sim-python/traffic-r | Traffic-R 输入已从 road-level 汇总改为 CityFlow lane-level `laneStates`，但其准确性依赖 roadnet `laneLinks.startLaneIndex` 与车辆 lane/distance API | 如果运行中的 Python 服务未重启到最新版本，后端仍可能拿不到 `laneStates`，模型输入会退化或请求无效 | 已记录 | 每次联调 Traffic-R 前必须重启 `sim-python` 并在 `sim.frame.data.laneStates` 中确认每个路口包含 `WT/WL/ST/SL/ET/EL/NT/NL` 与 4-cell 数据 |

| RISK-026 | P1 | backend/sim-python/frontend | `cityflow.frame-poll-interval-ms=100` 只表示 Spring 调度器在上一轮结束后等待 100ms 再尝试下一轮，不保证前端真实 10 FPS；真实 CityFlow `/frame` 是请求驱动推进，高倍速会在一次请求内连续执行多步并返回完整车辆、道路、laneStates JSON，车辆多时单帧可能达到数百毫秒到数秒 | 前端表现为车辆移动一段后等待新帧，高倍速越大越明显；如果同步策略每帧都下发 actions 或下发后额外拉取 frame，会进一步放大卡顿 | 已缓解 | 已移除 actions 后额外 `nextFrame()`，Fixed-Time/Max-Pressure 改为 10s 决策节流，前端插值改为跟随真实帧间隔；后续如需真正高倍速平滑演示，应将 CityFlow 改为后台连续步进并缓存最新 frame，REST/WebSocket 只读取快照 |
| RISK-027 | P1 | backend/sim-python/session | 多个仿真 session 并行时，每个 CityFlow Engine 都会占用独立 worker 和 CPU | 高倍速或 stress 场景并发过多会降低所有会话帧率 | 已处理 | 支持多个独立 `sid` 并行，但保留 `SIM_MAX_ACTIVE_SESSIONS` 资源上限；stop 和自然结束都会释放 Engine、worker、EV 状态与后端策略状态 |
| RISK-028 | P1 | deployment/sim-python | CityFlow 公网多人共享时，需要避免会话互相清理，同时保证接口不裸奔 | 错误的 owner 清理会终止他人仿真；缺少令牌会允许未授权调用 | 已处理 | 保留 `CITYFLOW_API_TOKEN` 认证；取消 owner/client 会话归属和自动互删，所有运行态操作按唯一 `sid` 定位 |
| RISK-029 | P1 | deployment/performance | 阿里云 4 核 8G 同时运行多个高倍速 CityFlow session 时，CPU 和响应时间会急剧恶化 | 前端帧率下降、策略下发延迟、多人共享体验变差 | 已处理 | 增加 `SIM_MAX_ACTIVE_SESSIONS`、`SIM_MAX_SPEED`、`SIM_VISIBLE_VEHICLE_LIMIT`、`SIM_MAX_REQUEST_BYTES`，并新增 `ALIYUN_CITYFLOW_DEPLOYMENT.md` 记录推荐值 |
| RISK-030 | P1 | emergency/strategy | 应急车完成或从 CityFlow 消失后，持久信号 override 曾可能残留并继续覆盖 RL | RL action 显示已下发但下一 tick 又被旧绿波相位覆盖 | 已处理 | override 按 EV 记录所有权；车辆离开后释放其全部 override；会话停止或自然结束时清空全部 EV 状态 |

| RISK-031 | P1 | backend/database/runtime | 第一阶段已接入运行时落库，但 Traffic-R 失败请求仍只能通过日志和 fallback 事件间接复盘，尚未把失败 prompt/request 逐条写入 `traffic_r_inference_log` | Agent 能查询成功推理、控制决策、fallback 和帧快照，但对失败推理链路的精确输入输出复盘仍不完整 | 待处理 | 后续在 Traffic-R 异步调度层增加带 `sid` 的推理审计上下文，失败、超时、空响应均写入数据库 |
| RISK-032 | P2 | backend/agent/mcp | 已新增 `/api/v1/agent/tools/**` 查询入口，但百炼 MCP 平台尚未完成工具注册，后端也尚未识别 MCP 调用身份 | 后端接口可用不等于百炼 Agent 已能自动调用；如果直接暴露公网还缺少工具级鉴权和调用边界 | 待处理 | 在百炼平台配置 MCP/HTTP 工具前，先确定网关地址、认证方式、参数 schema 和只读权限；公网暴露时必须增加鉴权或内网代理 |
| RISK-033 | P2 | backend/agent/audit | Agent 工具调用审计已支持 `messageId` 参数写入 `agent_tool_call`，但百炼 MCP 适配层必须负责先创建 `agent_conversation` / `agent_message` 并传回 `messageId` | 如果 MCP 直接调用工具但不传 `messageId`，工具仍可返回真实数据，但业务库无法串起“用户问题 -> 工具调用 -> Agent 回答”的完整证据链 | 已缓解 | 百炼 MCP 网关配置时，把会话创建、消息写入和工具调用串起来；继续禁止保存 API Key、认证头或过大的结果 payload |
