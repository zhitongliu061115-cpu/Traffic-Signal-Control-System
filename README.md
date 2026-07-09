# Traffic-Signal-Control-System

交通信号控制与 CityFlow 数字孪生仿真系统。

本项目面向小学期实训开发，目标是在 10 天内完成一个可运行、可演示、可扩展的交通信号仿真系统。当前阶段优先完成 **Spring Boot 主后端 + Python CityFlow 仿真服务 + Vue 前端实时渲染** 的主链路，先保证路网和车辆动态可视化真实打通，再逐步接入控制策略、数据库历史记录、应急绿波和智能体辅助功能。

## 当前开发目标

今日主线只做可视化仿真，不接控制策略：

```text
Vue 前端
  -> Spring Boot REST / WebSocket
  -> Python CityFlow 仿真服务
  -> CityFlow Engine
```

前端只允许访问 Spring Boot 主后端，不直接连接 Python 仿真服务。Spring Boot 负责统一协议、会话管理、数据库落地、WebSocket 推送和后续权限审计；Python 服务只负责 CityFlow / RL / Max-Pressure 等仿真和算法计算。

## 目录结构

```text
Traffic-Signal-Control-System/
|-- backend/        Spring Boot 主后端
|-- sim-python/     Python CityFlow 仿真服务
|-- sys-frontend/   Vue + TypeScript 前端大屏
|-- docs/           项目文档、接口规范、架构说明
|-- rules.md        团队 AI 辅助开发与协作规则
`-- README.md       项目总览
```

## 核心模块

- `可视化仿真`：加载 CityFlow 路网，实时渲染道路、路口、车辆、信号相位和拥堵状态。
- `仿真会话`：创建、启动、暂停和停止一次 CityFlow 仿真运行。
- `数据库记录`：保存场景、路网、相位、仿真会话、指标快照和后续操作日志。
- `控制策略`：预留 FixedTime、RL、Max-Pressure 等策略扩展点，当前阶段不进入可视化主链路。
- `应急绿波`：预留应急车辆优先通行模块，后续接入路径规划和优先控制。
- `智能体`：预留自然语言查询、拥堵解释、调度建议和报告生成模块。

## 文档入口

- [项目结构说明](docs/PROJECT_STRUCTURE.md)
- [后端文档入口](backend/docs/README.md)
- [阶段 2 调用链说明](backend/docs/CALL_CHAIN.md)
- [后端架构说明](backend/docs/BACKEND_ARCHITECTURE.md)
- [技术设计说明](backend/docs/TECHNICAL_DESIGN.md)
- [接口协作规范](backend/docs/API_GUIDELINES.md)
- [CFRP 前后端通信协议](docs/CFRP-1.0-前后端通信协议.md)
- [Git 协作规范](docs/GIT_GUIDELINES.md)
- [团队 Prompt 规范](docs/PROMPTS.md)

## 启动方式

后端：

```sh
cd backend
mvn compile
mvn spring-boot:run
```

如果 Windows 用户名包含中文，必须从 `backend` 目录启动 Maven。`backend/.mvn/maven.config` 已将 Maven 本地仓库和临时目录固定到模块内的 ASCII 路径，避免 `spring-boot:run` 因中文用户目录转码导致依赖 classpath 失效。

前端：

```sh
cd sys-frontend
npm install
npm run dev
```

Python CityFlow 仿真服务后续应单独放置，不再混入 `backend` 主后端目录。

Python 仿真服务：

```sh
cd sim-python
python app/server.py --host 127.0.0.1 --port 9000
```
