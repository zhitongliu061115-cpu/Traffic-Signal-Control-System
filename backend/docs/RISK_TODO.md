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
| RISK-008 | P2 | sim-python/CityFlow | CityFlow 0.1 没有 `get_tl_phase` 方法 | Python 无法直接从 Engine 读取当前信号相位，后续策略控制时可能出现相位状态不同步 | 待处理 | 接入控制策略时由 Python session 记录每次 `set_tl_phase` 的相位，并随 frame 返回 |
| RISK-009 | P2 | sim-python/CityFlow | 真实 CityFlow 车辆位置由 lane + distance 近似映射到 road polyline | 当前可用于可视化，但 laneLink 转弯曲线和车道偏移还不够精细 | 待处理 | 前端稳定后再按 roadLink/laneLink 曲线优化车辆坐标 |
| RISK-010 | P2 | sim-python/CityFlow | Windows Codex 环境无法直接访问用户 WSL `traffic-rl` 环境 | 本地自动化测试只能覆盖 mock 模式和代码编译，真实 CityFlow 模式需要在用户 WSL 中手动验证 | 已记录 | 每次修改真实模式后，必须在 WSL `traffic-rl` 中运行启动和 frame 接口验证 |
