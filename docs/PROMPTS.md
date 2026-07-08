# 团队 Prompt 模板

本文件保存团队可复用的 AI 辅助开发提示词。使用时请先说明当前模块、输入上下文、期望输出和验收标准，避免 AI 生成脱离项目架构的代码。

## 后端接口实现

```text
请在 backend 的 Spring Boot 项目中实现 <接口名称>。

必须遵守：
- 接口路径使用 /api/v1 前缀。
- REST 响应使用 ApiResponse<T> 结构。
- 字段命名遵守 backend/docs/API_GUIDELINES.md。
- Controller 只做参数接收、校验和响应封装。
- 业务逻辑放到对应 Service。
- 外部 Python CityFlow 调用只能通过 CityFlowClient。
- 不要让前端直接访问 Python 服务。
- 不要把控制策略逻辑写进 simulation 主链路。

请同时补充最小编译验证方式。
```

## 前端大屏组件

```text
请在 sys-frontend 中实现 <组件名称>。

上下文：
- 前端使用 Vue + TypeScript。
- API 字段以 backend/docs/API_GUIDELINES.md 和 CFRP 协议为准。
- 当前阶段优先保证 CityFlow 路网和车辆实时渲染准确。

要求：
- 不新增无必要第三方依赖。
- 组件状态清晰，样式与现有项目一致。
- 车辆渲染应按 vehicles[].id 复用对象。
- WebSocket 收到 sim.frame 后做动画插值。
- 给出如何验证渲染正确性的步骤。
```

## Python CityFlow 服务接口

```text
请在独立 Python CityFlow 服务中实现 <接口名称>。

必须遵守：
- Python 服务只被 Spring Boot 调用，不直接暴露给前端。
- 路网返回结构需要与 RoadnetResponse / CFRP 协议对齐。
- 仿真帧返回结构需要与 SimFrameData 对齐。
- 如果 CityFlow API 无法直接给出 x/y/angle，需要根据 roadnet 和车辆位置计算。
- 不要把 Spring Boot 的数据库逻辑写进 Python 服务。

当前最小接口：
- GET /cityflow/scenes/{sceneId}/roadnet
- POST /cityflow/simulations
- GET /cityflow/simulations/{sid}/frame
```

## 信号控制策略

```text
请在 backend 的 strategy 模块中实现 <策略名称>。

约束：
- 新策略必须实现 TrafficSignalController。
- 输入使用 ControlRequest 或后续扩展 DTO。
- 输出使用 ControlDecision。
- 不要直接修改 SimulationController。
- 不要绕过安全校验直接下发信号控制。
- RL 效果不稳定时，Max-Pressure 可以作为工程 fallback。

请补充策略适用场景、输入指标、输出动作和边界测试。
```

## 技术文档补充

```text
请根据当前代码补充 <文档名称>。

要求：
- 使用中文。
- 说明模块职责、调用链和边界。
- 明确哪些内容当前已实现，哪些只是预留。
- 如果涉及接口字段，必须和 backend/docs/API_GUIDELINES.md / CFRP 协议一致。
- 如果涉及数据库，必须说明对应 Flyway 脚本。
```
