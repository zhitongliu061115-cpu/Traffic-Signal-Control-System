# 后端服务说明

`backend` 是本项目的 **Spring Boot 主后端**。它负责对前端提供统一 REST / WebSocket 接口，并作为 Python CityFlow 仿真服务、数据库、控制策略和后续智能体能力之间的业务编排层。

当前阶段目标是先打通可视化仿真链路：

```text
前端 Vue
  -> Spring Boot 主后端
  -> Python CityFlow 仿真服务
  -> CityFlow Engine
```

前端不得直接连接 Python 服务。这样可以保证协议、数据库、权限、日志、策略切换和异常处理都集中在 Spring Boot 主后端内。

阶段 2 的完整调用链见 `docs/CALL_CHAIN.md`。后端代码必须保持这个方向：前端入口在 Controller，Python 调用只经过 `CityFlowClient`，实时帧只通过 `SimulationWebSocketHandler` 推送。

## 技术栈

- Java 17
- Spring Boot 3
- Spring Web
- Spring WebSocket
- Spring Data JPA
- Flyway
- H2：本地编译和轻量验证
- PostgreSQL：正式数据库部署建议

## 包结构

```text
src/main/java/com/traffic
|-- common        通用响应、异常处理、时间工具等公共能力
|-- config        Spring 配置、跨域配置、WebSocket 配置
|-- cityflow      Python CityFlow 服务访问边界
|-- roadnet       路网 DTO 与静态路网业务编排
|-- scene         场景接口、场景元数据和路网导入边界
|-- simulation    仿真会话、帧轮询、WebSocket 推送
|-- strategy      信号控制策略扩展点
|-- metrics       指标聚合和历史快照边界
|-- emergency     应急车辆与绿波控制预留模块
|-- agent         智能体问答和调度建议预留模块
`-- audit         操作审计和控制日志预留模块
```

## 今日已实现的主链路骨架

1. `SceneController` 暴露 `GET /api/v1/scenes/{sceneId}/roadnet`。
2. `RoadnetService` 负责路网业务编排。
3. `CityFlowClient` 负责调用 Python CityFlow 服务。
4. `SimulationController` 负责创建和控制仿真会话。
5. `SimulationSessionRegistry` 暂存运行中的仿真会话。
6. `SimulationFrameScheduler` 定时向 Python 服务拉取仿真帧。
7. `SimulationWebSocketHandler` 向前端推送 CFRP `sim.frame` 消息。

## 当前不实现的内容

以下模块只保留边界，不应混入当前可视化主链路：

- RL / LightGPT 控制策略
- Max-Pressure 控制策略
- 应急绿波控制
- 智能体自动调度
- 权限系统和人工接管

这些能力后续应通过既有包和接口扩展，不能直接改动 `simulation` 的可视化链路。

## Python CityFlow 服务约定

Spring Boot 预期 Python 服务提供以下接口：

```http
GET  /cityflow/scenes/{sceneId}/roadnet
POST /cityflow/simulations
GET  /cityflow/simulations/{sid}/frame
```

Spring Boot 的配置入口位于：

```text
src/main/resources/application.yml
```

其中 `cityflow.base-url` 表示 Python 服务地址，默认是 `http://localhost:9000`。

## 数据库初始化

数据库迁移脚本位于：

```text
src/main/resources/db/migration/V1__init_core_tables.sql
```

当前已包含：

- `cityflow_scene`
- `cityflow_intersection`
- `cityflow_road`
- `cityflow_road_link`
- `cityflow_phase`
- `simulation_session`
- `simulation_metric_snapshot`

原则：高频车辆位置通过 WebSocket 实时推送，不逐帧全量入库；数据库只保存静态路网、会话记录和指标快照。

## 连接现有 PostgreSQL 数据库

当前本地已有 `traffic_signal` 数据库时，使用 `postgres` profile 启动后端：

```powershell
$env:TRAFFIC_DB_PASSWORD="你的数据库密码"
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

默认连接 `jdbc:postgresql://localhost:5432/traffic_signal`，用户名为 `postgres`。可通过 `TRAFFIC_DB_URL`、`TRAFFIC_DB_USERNAME`、`TRAFFIC_DB_PASSWORD` 覆盖。

为了不改动已经建好的数据库表结构，`postgres` profile 暂时关闭 Flyway，且 Hibernate 不自动建表或改表。详细说明见 `docs/DATABASE_CONNECTION.md`。

当前已提供最小数据库读写验证接口：

```http
GET   /api/v1/database/status
GET   /api/v1/intersections
GET   /api/v1/intersections/{code}
PATCH /api/v1/intersections/{code}/status
```

其中 `PATCH /api/v1/intersections/{code}/status` 只更新路口状态字段，用于验证后端具备修改数据库能力。

## 编译验证

```sh
cd backend
mvn compile
```
