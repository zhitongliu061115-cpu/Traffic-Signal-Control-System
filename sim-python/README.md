# Python CityFlow 仿真服务

`sim-python` 是独立的 Python 仿真服务，只对 Spring Boot 主后端开放。前端不得直接调用本服务。

当前阶段只提供可视化仿真需要的 3 个最小接口：

```http
GET  /cityflow/scenes/{sceneId}/roadnet
POST /cityflow/simulations
GET  /cityflow/simulations/{sid}/frame
```

## 运行方式

本服务当前使用 Python 标准库实现 HTTP 服务，不需要额外安装依赖。

```sh
cd sim-python
python app/server.py --host 127.0.0.1 --port 9000
```

Spring Boot 默认会访问：

```text
http://localhost:9000
```

## 数据文件

默认场景：

```text
data/jinan_3x4/roadnet_3_4.json
data/jinan_3x4/flow_3_4_jinan_real.json
```

## 当前实现边界

当前版本优先保证接口联调和前端可视化，不直接接控制策略：

- 路网接口会解析 CityFlow roadnet 并返回 CFRP `RoadnetResponse`。
- 仿真会话接口会创建内存态 session。
- frame 接口会推进轻量车辆位置模拟，返回 `SimFrameData`。
- 如果本机后续接入真实 CityFlow Engine，只需要替换 `cityflow_adapter.py` 内部实现，接口路径和返回结构保持不变。

## 验证命令

```sh
python app/server.py --port 9000
```

另开一个终端：

```sh
python -m unittest discover tests
```
