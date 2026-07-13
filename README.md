# Traffic-Signal-Control-System

交通信号控制与 CityFlow 数字孪生仿真系统。

本项目面向小学期实训开发，目标是在 10 天内完成一个可运行、可演示、可扩展的交通信号仿真系统。当前阶段已经打通 **Spring Boot 主后端 + Python CityFlow 仿真服务 + 临时 Three.js 前端实时渲染 + FixedTime / MaxPressure / Traffic-R 策略调度** 的主链路。

## 当前开发目标

当前主链路：

```text
临时 Three.js 前端 / 后续 Vue 前端
  -> Spring Boot REST / WebSocket
  -> Python CityFlow 仿真服务
  -> CityFlow Engine

Spring Boot
  -> Traffic-R /predict-batch（RL 测试时按需调用）
```

前端只允许访问 Spring Boot 主后端，不直接连接 Python 仿真服务或 Traffic-R 模型服务。Spring Boot 负责统一协议、会话管理、数据库落地、WebSocket 推送、策略调度和后续权限审计；Python CityFlow 服务只负责路网解析、CityFlow 后台推进、缓存快照和应用信号相位；Traffic-R 作为独立云端模型服务按需输出 RL 决策。

## 目录结构

```text
Traffic-Signal-Control-System/
|-- backend/        Spring Boot 主后端
|-- sim-python/     Python CityFlow 仿真服务
|-- cloud/          云端模型服务与测试脚本
|-- temp-three-frontend/ 临时 Three.js 联调前端
|-- sys-frontend/   Vue + TypeScript 前端大屏
|-- docs/           项目文档、接口规范、架构说明
|-- agent.md        软件开发与 AI 协作权威规范
|-- rules.md        兼容入口，指向 agent.md
`-- README.md       项目总览
```

## 核心模块

- `可视化仿真`：加载 CityFlow 路网，实时渲染道路、路口、车辆、信号相位和拥堵状态。
- `仿真会话`：创建、启动、暂停和停止一次 CityFlow 仿真运行。
- `数据库记录`：保存场景、路网、相位、仿真会话、指标快照和后续操作日志。
- `控制策略`：已接入 FixedTime、MaxPressure、Traffic-R / RL 三类策略，统一输出 `ControlDecision` 并下发给 CityFlow。
- `应急绿波`：预留应急车辆优先通行模块，后续接入路径规划和优先控制。
- `智能体`：预留自然语言查询、拥堵解释、调度建议和报告生成模块。

## 文档入口

- [项目简要介绍](docs/项目简介.md)
- [项目结构说明](docs/PROJECT_STRUCTURE.md)
- [公共工作台账](docs/WORK_TRACKER.md)
- [开发协作规范](agent.md)
- [Traffic-R1 算法资料索引](docs/TRAFFIC_R1_REFERENCES.md)
- [后端文档入口](backend/docs/README.md)
- [阶段 2 调用链说明](backend/docs/CALL_CHAIN.md)
- [后端架构说明](backend/docs/BACKEND_ARCHITECTURE.md)
- [技术设计说明](backend/docs/TECHNICAL_DESIGN.md)
- [接口协作规范](backend/docs/API_GUIDELINES.md)
- [CityFlow 云端部署指南](backend/docs/CITYFLOW_CLOUD_RUNBOOK.md)
- [Traffic-R 云端模型启动指南](backend/docs/TRAFFIC_R_CLOUD_RUNBOOK.md)
- [CFRP 前后端通信协议](docs/CFRP-1.0-前后端通信协议.md)
- [SUMO 仿真迁移计划](docs/SUMO_MIGRATION_PLAN.md)
- [Git 协作规范](docs/GIT_规范.md)
- [团队 Prompt 规范](docs/PROMPTS.md)

## 启动方式

后端：

```sh
cd backend
mvn compile
mvn spring-boot:run
```

如果 Windows 用户名包含中文，必须从 `backend` 目录启动 Maven。`backend/.mvn/maven.config` 已将 Maven 本地仓库和临时目录固定到模块内的 ASCII 路径，避免 `spring-boot:run` 因中文用户目录转码导致依赖 classpath 失效。

临时联调前端：

```sh
cd temp-three-frontend
npm.cmd install
npm.cmd run dev
```

正式 `sys-frontend` 后续按团队前端进度接入同一套 Spring Boot REST / WebSocket 接口。

Python CityFlow 仿真服务当前默认使用阿里云 24h 服务，本地 Spring Boot 默认连接：

```text
http://39.105.75.87:9000
```

如果需要本地 WSL 备用启动，请参考 [部署说明](backend/docs/DEPLOYMENT.md) 和 [CityFlow 云端部署指南](backend/docs/CITYFLOW_CLOUD_RUNBOOK.md)。
