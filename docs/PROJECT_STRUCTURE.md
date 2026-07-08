# 项目结构说明

## 总体结构

```text
Traffic-Signal-Control-System/
|-- backend/
|   |-- pom.xml
|   |-- README.md
|   `-- src/main/
|       |-- java/com/traffic/
|       |   |-- common/
|       |   |-- config/
|       |   |-- cityflow/
|       |   |-- roadnet/
|       |   |-- scene/
|       |   |-- simulation/
|       |   |-- strategy/
|       |   |-- metrics/
|       |   |-- emergency/
|       |   |-- agent/
|       |   `-- audit/
|       `-- resources/
|           |-- application.yml
|           `-- db/migration/
|-- sim-python/
|   |-- app/
|   |-- data/
|   |-- tests/
|   `-- README.md
|-- sys-frontend/
|   |-- package.json
|   `-- src/
|       |-- api/
|       |-- components/traffic/
|       |-- composables/
|       |-- router/
|       |-- stores/
|       |-- styles/
|       |-- types/
|       `-- views/
|-- docs/
|-- rules.md
`-- README.md
```

## 后端目录职责

`backend` 是 Spring Boot 主后端。它只负责主业务、协议、数据库和服务编排，不直接包含 CityFlow 引擎代码。

| 包名 | 职责 |
|---|---|
| `common` | 通用响应结构、异常处理、公共工具 |
| `config` | Spring 配置、跨域、WebSocket 注册 |
| `cityflow` | Python CityFlow 服务客户端边界 |
| `roadnet` | 静态路网 DTO 和路网业务处理 |
| `scene` | 场景接口、场景元数据和后续路网入库 |
| `simulation` | 仿真会话、仿真帧轮询、WebSocket 推送 |
| `strategy` | FixedTime、RL、Max-Pressure 等控制策略扩展点 |
| `metrics` | 指标统计、指标快照和历史查询 |
| `emergency` | 应急车辆、路径规划、绿波控制预留边界 |
| `agent` | 自然语言查询、拥堵解释、调度建议预留边界 |
| `audit` | 操作审计、控制日志、人工接管记录 |

## 前端目录职责

`sys-frontend` 负责实时可视化和交互页面。

| 目录 | 职责 |
|---|---|
| `api` | REST 和 WebSocket 客户端封装 |
| `components/traffic` | 路网、车辆、信号灯、指标等交通组件 |
| `composables` | 复用逻辑，例如 WebSocket 订阅、动画插值 |
| `stores` | Pinia 状态管理 |
| `types` | 前端共享 TypeScript 类型 |
| `views` | 大屏页面、仿真页面、后续调度页面 |

## Python CityFlow 服务位置

Python CityFlow 仿真服务作为独立服务维护，不放在 `backend` 目录下。

```text
sim-python/
|-- app/
|-- data/
|-- tests/
`-- README.md
```

Spring Boot 通过 HTTP 调用 Python 服务，前端不得直接访问 Python 服务。

## 当前阶段开发边界

当前只实现“路网与车辆实时可视化”：

```text
前端请求路网
  -> Spring Boot scene / roadnet
  -> Python CityFlow roadnet

前端连接 WebSocket
  -> Spring Boot simulation
  -> Spring Boot 定时拉取 Python frame
  -> 前端实时渲染车辆
```

控制策略、应急绿波和智能体模块暂时只保留包结构和接口边界。
