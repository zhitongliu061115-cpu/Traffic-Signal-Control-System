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
| RISK-008 | P2 | sim-python/CityFlow | CityFlow 0.1 没有 `get_tl_phase` 方法 | Python 无法直接从 Engine 读取当前信号相位，后续策略控制时可能出现相位状态不同步 | 已缓解 | 当前真实引擎模式按 roadnet 相位配置周期调用 `set_tl_phase`，并由 Python session 记录当前相位随 frame 返回；后续接入 RL/Max-Pressure 时仍需由策略模块统一维护相位状态 |
| RISK-009 | P2 | sim-python/CityFlow | 真实 CityFlow 车辆位置由 lane + distance 近似映射到 road polyline，当前 Python frame 未返回 laneLink 曲线或车辆在路口内部的精确坐标 | 前端已按 lane 做横向偏移，并在跨 road 跳变时禁用长距离直线插值，能缓解车道重叠和飞车；但车辆真实转弯轨迹仍是近似效果 | 已缓解 | 后续扩展 roadnet/frame 协议，返回 laneLink points 或更精确的车辆坐标，用于真实转弯动画 |
| RISK-010 | P2 | sim-python/CityFlow | Windows Codex 环境无法直接访问用户 WSL `traffic-rl` 环境 | 本地自动化测试只能覆盖 mock 模式和代码编译，真实 CityFlow 模式需要在用户 WSL 中手动验证 | 已记录 | 每次修改真实模式后，必须在 WSL `traffic-rl` 中运行启动和 frame 接口验证 |
| RISK-011 | P2 | backend | Windows 中文用户目录会导致 Spring Boot Maven 插件生成的 argfile/classpath 路径转码异常，表现为 `mvn spring-boot:run` 报 `NoClassDefFoundError: org/springframework/boot/SpringApplication` | 团队成员如果直接使用默认用户目录下的 Maven 缓存和临时目录，可能误以为后端不可用，影响联调效率 | 已处理 | 已新增 `backend/.mvn/maven.config`，将 Maven 本地仓库和 Java 临时目录固定到模块内 ASCII 路径；启动时必须先 `cd backend` 再执行 `mvn spring-boot:run` |
| RISK-012 | P1 | deployment | 当前本地真实 CityFlow 依赖 WSL `traffic-rl` 环境，云端部署不能直接照搬 WSL 启动方式 | 如果云端仍按本地 WSL/localhost 思路部署，Spring Boot 可能无法连接 Python CityFlow，导致仿真链路中断 | 已记录 | 云端必须将 Python CityFlow 作为独立 Linux 服务或容器部署，并按 `DEPLOYMENT.md` 配置 `cityflow.base-url` |
| RISK-013 | P3 | local-dev | 本地开发如果每次手动进入 WSL 并激活 `traffic-rl`，容易漏步骤或启动在错误目录 | 会降低联调效率，或误启动 mock/base 环境导致 Spring Boot 连接到错误服务 | 已处理 | 已新增 `scripts/start-cityflow-wsl.ps1` 和 `scripts/stop-cityflow-wsl.ps1`，本地开发优先使用脚本 |
| RISK-014 | P2 | backend/frontend | 为提升可视化流畅度，Spring Boot 默认以 `cityflow.frame-poll-interval-ms=200` 轮询 Python frame 接口，真实 CityFlow config `interval` 同步设置为 0.2 秒 | 画面更顺滑且仿真时间不快进；但会提高 Python CityFlow 服务调用频率，低性能机器或多人联调时可能增加 CPU 压力 | 已记录 | 演示优先使用 200ms；如果本机卡顿或 Python 服务压力过高，可在 `application.yml` 中调大轮询间隔，并同步调整 Python CityFlow interval 策略 |
