# Python CityFlow 仿真服务

`sim-python` 是独立的 Python 仿真算法服务，只允许 Spring Boot 主后端调用。前端不得直接连接本服务。

当前目标是打通“路网读取 + 仿真帧返回 + Spring Boot WebSocket 推送”的可视化链路。控制策略、数据库持久化、权限、日志和前端协议封装都归 Spring Boot 主后端管理。

## 服务边界

- 对外只暴露给 Spring Boot，不直接服务前端。
- 成功响应保持轻量 DTO，便于 Spring Boot 反序列化。
- 错误响应统一返回 `success/code/message/retryable`。
- 当前 `SIM_ENGINE_MODE=mock`，使用 roadnet/flow 文件生成确定性的可视化帧。
- `SIM_ENGINE_MODE=cityflow` 已预留，但真实 CityFlow Engine 适配尚未实现，启动时会明确失败，避免误用 mock 当成真实仿真。

## 最小接口

```http
GET  /health
GET  /cityflow/scenes/{sceneId}/roadnet
POST /cityflow/simulations
GET  /cityflow/simulations/{sid}/frame
```

### 健康检查

```http
GET /health
```

示例响应：

```json
{
  "status": "UP",
  "service": "sim-python",
  "version": "0.2.0",
  "engineMode": "mock",
  "sceneIds": ["jinan_3x4"],
  "activeSessions": 0
}
```

### 创建仿真

```http
POST /cityflow/simulations
Content-Type: application/json

{
  "sceneId": "jinan_3x4",
  "speed": 1.0
}
```

响应：

```json
{
  "sid": "run_xxxxxxxx",
  "sceneId": "jinan_3x4",
  "status": "created",
  "engineMode": "mock"
}
```

## 运行方式

```sh
cd sim-python
python app/server.py --host 127.0.0.1 --port 9000
```

Spring Boot 默认访问：

```text
http://localhost:9000
```

## 场景配置

场景统一登记在：

```text
data/scenes.json
```

当前默认场景：

```text
data/jinan_3x4/roadnet_3_4.json
data/jinan_3x4/flow_3_4_jinan_real.json
```

新增场景时，不要在代码里硬编码文件名，应先在 `data/scenes.json` 增加 `sceneId`、`roadnetFile`、`flowFile`。

## 已解决的风险

- 已增加 `/health`，Spring Boot 和联调人员可以确认 Python 服务是否启动、当前引擎模式和可用场景。
- 已增加标准错误码，未知场景、未知会话、非法 speed 不再混成 500。
- 已增加同一 `sid` 的帧推进锁，避免并发请求导致帧序号和仿真时间乱序。
- 已增加 `engineMode`，明确区分 mock 可视化帧和真实 CityFlow 引擎。
- 已将场景文件配置抽到 `data/scenes.json`，降低新增场景时修改代码的风险。

## 仍需接入真实 CityFlow 时提供的信息

接入真实 CityFlow Engine 前，需要你提供以下内容：

- 本机可运行的 CityFlow 示例脚本。
- CityFlow Python 包的导入方式，例如 `import cityflow` 是否可用。
- Engine 初始化需要的 config JSON 路径和字段结构。
- 当前版本可用 API：推进一步、获取车辆列表、车辆位置、车辆速度、道路/车道状态、信号相位读取或设置。
- roadnet/flow/config 文件的实际目录规划。

在这些信息明确之前，服务只保证可视化链路可跑，不宣称是真实 CityFlow Engine 输出。

## 验证命令

```sh
cd sim-python
python -m unittest discover tests
```
