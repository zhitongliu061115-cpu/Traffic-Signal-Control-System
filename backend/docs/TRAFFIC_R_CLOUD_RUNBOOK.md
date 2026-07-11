# Traffic-R 云端模型启动指南

本文档记录 Traffic-R / Traffic-R1 模型在 AutoDL 云端的启动方式，以及本地 Spring Boot 如何通过 SSH 隧道调用模型服务。当前项目默认 CityFlow 已部署在阿里云，Traffic-R 仍部署在 AutoDL，平时可以不启动；只有选择 `traffic-r` / `rl` 策略进行模型调度测试时才需要启动。

## 1. 当前部署信息

| 项目 | 当前值 |
|---|---|
| 云端平台 | AutoDL |
| Traffic-R 项目目录 | `/root/autodl-tmp/traffic-R1` |
| 服务脚本 | `/root/autodl-tmp/traffic-R1/traffic_r_service.py` |
| Python 虚拟环境 | `/root/autodl-tmp/traffic-R1/.venv-autodl` |
| 模型路径 | `/root/autodl-tmp/traffic-R1/models/Traffic-R1/huggingface` |
| 云端监听端口 | `6008` |
| 本地隧道端口 | `16008` |
| 健康检查 | `GET /health` |
| 批量决策接口 | `POST /predict-batch` |

后端当前默认 Traffic-R 地址仍是本地隧道：

```yaml
traffic-r:
  base-url: ${TRAFFIC_R_BASE_URL:http://127.0.0.1:16008}
  batch-predict-path: ${TRAFFIC_R_BATCH_PREDICT_PATH:/predict-batch}
```

因此本地 Spring Boot 不直接访问 AutoDL 公网端口，而是访问本地 `16008`，再由 SSH 隧道转发到 AutoDL 的 `127.0.0.1:6008`。

## 2. 上传或更新服务脚本

本地服务脚本位置：

```text
D:\Github\Traffic-Signal-Control-System\cloud\traffic-r\traffic_r_service.py
```

如果云端脚本需要更新，从本地上传到 AutoDL：

```powershell
scp -P <AutoDL SSH端口> `
  "D:\Github\Traffic-Signal-Control-System\cloud\traffic-r\traffic_r_service.py" `
  root@<AutoDL SSH地址>:/root/autodl-tmp/traffic-R1/traffic_r_service.py
```

示例中的 `<AutoDL SSH端口>` 和 `<AutoDL SSH地址>` 以 AutoDL 控制台显示为准。AutoDL 端口经常会变化，不要把旧端口当成固定值。

## 3. 云端启动模型服务

SSH 登录 AutoDL 后执行：

```bash
cd /root/autodl-tmp/traffic-R1
```

确认文件存在：

```bash
ls -lah traffic_r_service.py
ls -lah .venv-autodl/bin/python
ls -lah models/Traffic-R1/huggingface
```

启动 Traffic-R 服务：

```bash
WANDB_MODE=disabled \
PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python \
TOKENIZERS_PARALLELISM=false \
.venv-autodl/bin/python traffic_r_service.py \
  --host 0.0.0.0 \
  --port 6008 \
  --model-path /root/autodl-tmp/traffic-R1/models/Traffic-R1/huggingface \
  --max-new-tokens 1024 \
  --temperature 0.0 \
  --top-p 1.0 \
  --no-do-sample \
  --system-prompt-file prompts/prompt_commonsense.json
```

注意：

- 每一行末尾的反斜杠 `\` 后面不能有空格。
- `--model-path` 必须指向 HuggingFace 模型目录。
- 当前服务是严格模式，不允许启发式降级；模型没有输出合法四相位结果时会返回错误。
- Traffic-R 推理耗时较长，历史测试单轮批量推理大约为数秒到十几秒，后端已按低频异步策略调用。

## 4. 云端本机验证

另开一个 AutoDL SSH 终端，或在后台运行服务后执行：

```bash
curl http://127.0.0.1:6008/health
```

正常应包含类似字段：

```json
{
  "status": "UP",
  "service": "traffic-r1-online",
  "supportsBatch": true
}
```

如果没有 `supportsBatch=true`，说明启动的不是当前批量版本服务，Spring Boot 的 `/predict-batch` 调用会失败。

## 5. 本地开启 SSH 隧道

AutoDL 的 `6008` 通常不会映射到公网，因此本地需要开 SSH 隧道。PowerShell 示例：

```powershell
ssh -p <AutoDL SSH端口> -L 16008:127.0.0.1:6008 root@<AutoDL SSH地址>
```

示例：

```powershell
ssh -p 15200 -L 16008:127.0.0.1:6008 root@connect.westc.seetacloud.com
```

如果 AutoDL 控制台给出的地址或端口变化，必须以新的地址和端口为准。

隧道终端必须保持打开。关闭该 SSH 窗口后，本地 `127.0.0.1:16008` 会失效。

## 6. 本地验证模型接口

隧道打开后，在本地 PowerShell 验证：

```powershell
Invoke-RestMethod "http://127.0.0.1:16008/health"
```

批量接口建议用仓库里的测试脚本验证：

```powershell
cd D:\Github\Traffic-Signal-Control-System
python .\cloud\traffic-r\test_traffic_r_interface.py `
  --base-url http://127.0.0.1:16008 `
  --cases .\cloud\traffic-r\testdata\traffic_r_interface_cases_lane_level.json `
  --repeat 1
```

如果只想确认接口能返回，可以构造最小请求调用 `/predict-batch`，但正式验证建议使用测试脚本，因为它会检查：

- HTTP 状态是否成功。
- 返回是否包含所有路口决策。
- `phaseCode` 是否属于 `ETWT`、`NTST`、`ELWL`、`NLSL`。
- `parsedFromModel` 是否为 `true`。
- `rawOutput` 是否非空。
- 推理耗时是否被记录。

## 7. 本地 Spring Boot 配置

本地后端默认已经使用隧道地址：

```yaml
traffic-r:
  enabled: ${TRAFFIC_R_ENABLED:true}
  base-url: ${TRAFFIC_R_BASE_URL:http://127.0.0.1:16008}
  health-path: ${TRAFFIC_R_HEALTH_PATH:/health}
  predict-path: ${TRAFFIC_R_PREDICT_PATH:/predict}
  batch-predict-path: ${TRAFFIC_R_BATCH_PREDICT_PATH:/predict-batch}
  decision-interval-sec: ${TRAFFIC_R_DECISION_INTERVAL_SEC:10}
  timeout-sec: ${TRAFFIC_R_TIMEOUT_SEC:30}
  fallback-controller: ${TRAFFIC_R_FALLBACK_CONTROLLER:max-pressure}
```

通常无需额外设置。若需要显式指定：

```powershell
$env:TRAFFIC_R_BASE_URL="http://127.0.0.1:16008"
```

然后启动后端：

```powershell
cd D:\Github\Traffic-Signal-Control-System\backend
mvn spring-boot:run
```

前端选择 `rl` 或 `traffic-r` 策略时，后端才会调用 Traffic-R；选择 `max-pressure` 或 `fixed-time` 时不依赖 AutoDL 模型服务。

## 8. 推荐启动顺序

需要测试 RL 模型时：

1. 启动阿里云 CityFlow，确认 `http://39.105.75.87:9000/health` 可访问。
2. 登录 AutoDL，启动 Traffic-R 模型服务，监听 `0.0.0.0:6008`。
3. 本地打开 SSH 隧道：`16008 -> AutoDL 127.0.0.1:6008`。
4. 本地验证 `Invoke-RestMethod http://127.0.0.1:16008/health`。
5. 启动 Spring Boot。
6. 启动前端。
7. 前端选择 `rl` / `traffic-r` 策略创建仿真。

日常不测试 RL 时：

1. 保持阿里云 CityFlow 运行。
2. 不启动 AutoDL Traffic-R。
3. 前端选择 `max-pressure` 或 `fixed-time`。

## 9. 常见问题

### PowerShell 中 curl JSON 报错

PowerShell 的 `curl` 默认是 `Invoke-WebRequest` 别名，不等同 Linux curl。建议使用：

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:16008/health"
```

或显式调用：

```powershell
curl.exe http://127.0.0.1:16008/health
```

### 本地访问 `16008` 失败

检查：

1. AutoDL 模型服务是否正在运行。
2. SSH 隧道窗口是否仍打开。
3. AutoDL SSH 地址和端口是否已变化。
4. 云端服务是否监听 `6008`：

```bash
ss -lntp | grep 6008
curl http://127.0.0.1:6008/health
```

### 后端创建 RL 仿真没有模型效果

检查：

1. 前端是否选择 `rl` / `traffic-r`，而不是 `max-pressure`。
2. 后端日志中是否成功调用 `/predict-batch`。
3. 云端返回是否 `parsedFromModel=true`。
4. `backend/docs/TRAFFIC_R_DECISION_AUDIT.md` 或后端审计日志中是否有模型请求和响应。

### 模型启动后 GPU 没有占用

先请求一次 `/predict-batch`。模型可能在启动时加载，但 GPU 利用率只有推理时才明显上升。

### 不要把 Traffic-R 端口直接公网开放

当前推荐做法是 SSH 隧道访问 AutoDL 的 `6008`。不要为了方便直接把 Traffic-R 推理端口暴露到公网；模型服务没有设计成公网 API 网关。
