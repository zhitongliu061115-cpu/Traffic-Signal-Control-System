# Python CityFlow 仿真服务

`sim-python` 是独立的 Python 仿真算法服务，只允许 Spring Boot 主后端调用。前端不得直接连接本服务。

当前目标是打通“路网读取 + 仿真帧返回 + Spring Boot WebSocket 推送”的可视化链路。控制策略、数据库持久化、权限、日志和前端协议封装都归 Spring Boot 主后端管理。

## 服务边界

- 对外只暴露给 Spring Boot，不直接服务前端。
- 成功响应保持轻量 DTO，便于 Spring Boot 反序列化。
- 错误响应统一返回 `success/code/message/retryable`。
- 默认 `SIM_ENGINE_MODE=mock`，使用 roadnet/flow 文件生成确定性的可视化帧。
- `SIM_ENGINE_MODE=cityflow` 时会接入真实 CityFlow Engine，并使用场景配置中的 roadnet/flow 动态生成 CityFlow config。
- 真实 CityFlow 当前仅在 WSL Ubuntu 的 conda 环境 `traffic-rl` 中验证通过，Windows/base Python 不能直接运行真实引擎。

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

## 真实 CityFlow 模式

真实模式必须在装有 CityFlow 的 Python 环境中运行。当前已验证环境：

```text
WSL Ubuntu
conda env: traffic-rl
python: /home/huangchengjun666/miniconda3/envs/traffic-rl/bin/python
cityflow: 0.1
```

启动示例：

```sh
cd /mnt/d/Github/Traffic-Signal-Control-System/sim-python
conda activate traffic-rl
SIM_ENGINE_MODE=cityflow python app/server.py --host 0.0.0.0 --port 9000
```

另开一个 WSL 终端验证：

```sh
curl http://127.0.0.1:9000/health

curl -X POST http://127.0.0.1:9000/cityflow/simulations \
  -H "Content-Type: application/json" \
  -d '{"sceneId":"jinan_3x4","speed":1.0}'

curl http://127.0.0.1:9000/cityflow/simulations/{sid}/frame
```

`/health` 中必须看到：

```json
{
  "engineMode": "cityflow"
}
```

注意：

- Spring Boot 仍然只通过 HTTP 调用 Python 服务。
- 前端仍然不能直连 Python 服务。
- 真实模式会读取 `data/scenes.json` 中的 roadnet/flow 文件，并为每个仿真会话生成临时 CityFlow config。
- CityFlow 0.1 没有 `get_tl_phase`，后续接入控制策略时需要由 Python session 自己记录当前设置过的相位。
- 创建请求始终生成新的独立 `sid`，多个会话可并行运行；新建会话不再因为旧会话数量达到 `SIM_MAX_ACTIVE_SESSIONS` 而返回 429，`SIM_MAX_ACTIVE_SESSIONS=0` 表示不设置创建数量上限。不再按 `X-CityFlow-Client` 区分归属或清理旧会话。
- `stop` 会释放对应 worker、CityFlow Engine、EV 状态和临时配置。最后发车时间已到且活跃车辆数归零时也会自动释放；若车辆无法排空，超过 `SIM_SESSION_DRAIN_TIMEOUT_SECONDS`（默认 600 秒仿真时间）后强制释放，并返回一次 `status=finished` 的终态帧。除此之外，服务还会按 `SIM_SESSION_IDLE_TTL_SECONDS` 清理暂停/创建后长期无访问的 idle 会话，按 `SIM_SESSION_ABANDONED_TTL_SECONDS` 清理 running 但长期没有后端请求的遗弃会话，并按 `SIM_SESSION_MAX_LIFETIME_SECONDS` 清理超出生存期的会话。

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
- 已实现 `SIM_ENGINE_MODE=cityflow` 的真实 CityFlow Engine 接入入口。

## 仍需接入真实 CityFlow 时提供的信息

真实 CityFlow Engine 已经可以创建会话并推进帧。后续若要加入控制策略，需要继续补：

- 从控制器传入相位决策，并调用 `set_tl_phase`。
- 由 session 记录当前信号相位，因为 CityFlow 0.1 没有 `get_tl_phase`。
- 根据 CityFlow 车辆距离和 lane 信息进一步校准前端车辆位置。

## 验证命令

```sh
cd sim-python
python -m unittest discover tests
```
