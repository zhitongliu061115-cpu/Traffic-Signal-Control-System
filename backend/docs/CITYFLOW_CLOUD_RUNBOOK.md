# CityFlow 云端部署与启动指南

本文档记录本项目当前将 CityFlow 部署到阿里云服务器后的实际路径、环境配置、启动命令、验证方式和后续更新流程。当前部署目标是：CityFlow 在阿里云 24 小时运行，本地 Spring Boot 默认直接连接云端 CityFlow。

## 1. 当前部署信息

| 项目 | 当前值 |
|---|---|
| 云服务器公网 IP | `39.105.75.87` |
| CityFlow 服务端口 | `9000` |
| 云端项目根目录 | `/opt/Traffic-Signal-Control-System` |
| CityFlow 服务目录 | `/opt/Traffic-Signal-Control-System/sim-python` |
| Python 服务入口 | `/opt/Traffic-Signal-Control-System/sim-python/app/server.py` |
| Conda 环境 | `cityflow39` |
| Spring Boot 默认 CityFlow 地址 | `http://39.105.75.87:9000` |
| 会话标识 | Python 创建并返回的唯一 `sid` |
| 当前团队 token | `jLEc-o3L16migUKQ7f_OlH94qsjEstFf` |

当前云端目录应至少包含：

```text
/opt/Traffic-Signal-Control-System/
  sim-python/
    app/
    data/
    README.md
  docs/
    ALIYUN_CITYFLOW_DEPLOYMENT.md
```

## 2. 2026-07-11 云端更新记录

本次更新用于修复应急绿波结束后继续覆盖 RL 相位的问题，并调整 CityFlow 会话生命周期：

- 应急车辆完成、离开 CityFlow 或注入失败后，释放该车辆持有的全部信号覆盖，后续 RL 可以继续控制路口。
- 应急调度、CityFlow step 和 RL action 通过一致的引擎锁访问 CityFlow，避免并发修改引擎状态。
- 创建新仿真不再停止已有仿真，不再按 `X-CityFlow-Client` 区分会话归属；所有操作统一通过 `sid` 定位。
- 新建会话不再因为旧会话数量达到 `SIM_MAX_ACTIVE_SESSIONS` 而返回 429；`SIM_MAX_ACTIVE_SESSIONS=0` 表示不设置创建数量上限。旧会话通过 stop、自然结束、idle TTL 和最大生存期自动释放。
- 调用 stop 时释放 worker、CityFlow Engine、应急状态和临时配置目录。
- 最后一批车辆已经发出且路网车辆清空后自动释放会话；如果车辆始终无法排空，超过 `SIM_SESSION_DRAIN_TIMEOUT_SECONDS=600` 后强制释放。
- 最后一帧返回 `status=finished`，Spring Boot 收到后停止轮询并清理策略运行状态。

本次需要更新的云端目录为：

```text
/opt/Traffic-Signal-Control-System/sim-python/app
/opt/Traffic-Signal-Control-System/sim-python/data
/opt/Traffic-Signal-Control-System/sim-python/tests
/opt/Traffic-Signal-Control-System/sim-python/README.md
```

## 3. 云端 Python 环境

CityFlow 不应使用 Python 3.14 环境。当前建议使用 Conda 创建 Python 3.9 环境：

```bash
conda tos accept --override-channels --channel https://repo.anaconda.com/pkgs/main
conda tos accept --override-channels --channel https://repo.anaconda.com/pkgs/r

conda create -n cityflow39 python=3.9 -y
conda activate cityflow39
python --version
```

确认输出为：

```text
Python 3.9.x
```

安装系统编译依赖：

```bash
apt update
apt install -y git build-essential cmake curl lsof
```

CityFlow 需要从源码安装：

```bash
cd /opt
git clone https://github.com/cityflow-project/CityFlow.git
cd CityFlow
pip install .
```

验证：

```bash
python -c "import cityflow; print('cityflow import ok'); print(cityflow.Engine)"
```

## 4. 手动启动 CityFlow 服务

进入云端服务目录：

```bash
cd /opt/Traffic-Signal-Control-System/sim-python
conda activate cityflow39
```

启动真实 CityFlow 模式：

```bash
SIM_ENGINE_MODE=cityflow \
CITYFLOW_API_TOKEN="jLEc-o3L16migUKQ7f_OlH94qsjEstFf" \
SIM_MAX_ACTIVE_SESSIONS=0 \
SIM_SESSION_IDLE_TTL_SECONDS=300 \
SIM_SESSION_ABANDONED_TTL_SECONDS=300 \
SIM_SESSION_MAX_LIFETIME_SECONDS=1800 \
SIM_SESSION_CLEANUP_INTERVAL_SECONDS=30 \
SIM_SESSION_DRAIN_TIMEOUT_SECONDS=600 \
SIM_MAX_SPEED=10 \
SIM_VISIBLE_VEHICLE_LIMIT=300 \
SIM_MAX_REQUEST_BYTES=1048576 \
SIM_REALTIME_TICK_SECONDS=0.1 \
SIM_MIN_REALTIME_TICK_SECONDS=0.02 \
python app/server.py --host 0.0.0.0 --port 9000
```

注意：

- `--host` 必须是 `0.0.0.0`，不能是 `127.0.0.1`，否则公网无法访问。
- `CITYFLOW_API_TOKEN` 必须和本地 Spring Boot 配置一致。
- `SIM_MAX_ACTIVE_SESSIONS=0` 表示创建会话时不按数量拒绝请求；如保留旧值 `4`，当前版本也不会因为达到该值而返回 429，只作为 `/health.maxActiveSessions` 的软配置展示。
- `SIM_SESSION_IDLE_TTL_SECONDS=300` 表示创建后未 start 或 pause 后长期无访问的 idle 会话会在约 5 分钟后被后台清理。
- `SIM_SESSION_ABANDONED_TTL_SECONDS=300` 表示 running 会话如果约 5 分钟没有任何 `/frame`、`/actions`、`/pause`、`/stop` 等后端请求，会被视为遗弃并自动释放。
- `SIM_SESSION_MAX_LIFETIME_SECONDS=1800` 表示单个会话最长保留约 30 分钟，避免异常运行会话长期占用 Engine。
- `SIM_SESSION_CLEANUP_INTERVAL_SECONDS=30` 表示后台清理线程每 30 秒检查一次过期会话；创建新会话和访问 `/health` 时也会触发一次清理。
- `SIM_SESSION_DRAIN_TIMEOUT_SECONDS=600` 表示最后发车后最多再等待 600 秒仿真时间排空路网，避免死锁车辆永久占用 Engine。
- 创建新仿真不会清理已有仿真；每个 `sid` 持有独立 CityFlow Engine。
- 调用 stop 或场景自然结束后，服务会释放 worker、Engine、应急状态和临时配置目录，`/health.activeSessions` 随之减少。

## 5. 云端本机验证

查看服务是否监听公网地址：

```bash
ss -lntp | grep 9000
```

正常应看到：

```text
LISTEN ... 0.0.0.0:9000 ...
```

健康检查：

```bash
curl http://127.0.0.1:9000/health
```

正常应包含：

```json
{
  "status": "UP",
  "engineMode": "cityflow",
  "maxActiveSessions": 0,
  "sessionAbandonedTtlSeconds": 300,
  "sessionIdleTtlSeconds": 300,
  "sessionMaxLifetimeSeconds": 1800,
  "sessionCleanupIntervalSeconds": 30,
  "sessionDrainTimeoutSeconds": 600,
  "activeSessions": 0,
  "sceneIds": ["jinan_3x4", "jinan_3x4_stress"]
}
```

测试受保护接口：

```bash
curl \
  -H "X-CityFlow-Token: jLEc-o3L16migUKQ7f_OlH94qsjEstFf" \
  -H "X-CityFlow-Client: hcj" \
  http://127.0.0.1:9000/cityflow/scenes/jinan_3x4/roadnet
```

## 6. 本地公网验证

在 Windows PowerShell 中测试端口：

```powershell
Test-NetConnection 39.105.75.87 -Port 9000
```

必须看到：

```text
TcpTestSucceeded : True
```

健康检查：

```powershell
Invoke-RestMethod "http://39.105.75.87:9000/health"
```

受保护接口：

```powershell
Invoke-RestMethod `
  -Uri "http://39.105.75.87:9000/cityflow/scenes/jinan_3x4/roadnet" `
  -Headers @{
    "X-CityFlow-Token" = "jLEc-o3L16migUKQ7f_OlH94qsjEstFf"
    "X-CityFlow-Client" = "hcj"
  }
```

如果本地 `Test-NetConnection` 失败，但云端 `curl 127.0.0.1:9000/health` 成功，优先检查阿里云安全组和系统防火墙。

## 7. 阿里云安全组与防火墙

阿里云 ECS 安全组入方向需要允许：

```text
协议类型: TCP
端口范围: 9000/9000
授权对象: 0.0.0.0/0
策略: 允许
```

如果系统防火墙启用：

```bash
ufw status
ufw allow 9000/tcp
ufw reload
```

或：

```bash
firewall-cmd --state
firewall-cmd --permanent --add-port=9000/tcp
firewall-cmd --reload
```

## 8. Spring Boot 当前配置

本地 Spring Boot 当前默认已经写入云端 CityFlow：

```yaml
cityflow:
  base-url: ${CITYFLOW_BASE_URL:http://39.105.75.87:9000}
  api-token: ${CITYFLOW_API_TOKEN:jLEc-o3L16migUKQ7f_OlH94qsjEstFf}
  client-id: ${CITYFLOW_CLIENT_ID:hcj}
```

因此一般情况下直接启动后端即可：

```powershell
cd D:\Github\Traffic-Signal-Control-System\backend
mvn spring-boot:run
```

`CITYFLOW_CLIENT_ID` 当前仅为兼容保留，可以继续配置，但不会用于会话隔离或权限判断：

```powershell
$env:CITYFLOW_CLIENT_ID="your-name"
```

## 9. systemd 方式 24 小时运行

先创建环境变量文件，沿用当前团队 token：

```bash
mkdir -p /etc/traffic-signal
chmod 700 /etc/traffic-signal
vim /etc/traffic-signal/cityflow.env
```

写入：

```ini
SIM_ENGINE_MODE=cityflow
CITYFLOW_API_TOKEN=当前团队token
SIM_MAX_ACTIVE_SESSIONS=0
SIM_SESSION_IDLE_TTL_SECONDS=300
SIM_SESSION_ABANDONED_TTL_SECONDS=300
SIM_SESSION_MAX_LIFETIME_SECONDS=1800
SIM_SESSION_CLEANUP_INTERVAL_SECONDS=30
SIM_SESSION_DRAIN_TIMEOUT_SECONDS=600
SIM_MAX_SPEED=10
SIM_VISIBLE_VEHICLE_LIMIT=300
SIM_MAX_REQUEST_BYTES=1048576
SIM_REALTIME_TICK_SECONDS=0.1
SIM_MIN_REALTIME_TICK_SECONDS=0.02
SIM_AUTO_SIGNAL_CYCLE=false
```

限制文件权限：

```bash
chmod 600 /etc/traffic-signal/cityflow.env
```

创建服务文件：

```bash
vim /etc/systemd/system/cityflow-sim.service
```

写入：

```ini
[Unit]
Description=Traffic Signal CityFlow Simulation Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/Traffic-Signal-Control-System/sim-python
EnvironmentFile=/etc/traffic-signal/cityflow.env
ExecStart=/root/miniconda3/envs/cityflow39/bin/python /opt/Traffic-Signal-Control-System/sim-python/app/server.py --host 0.0.0.0 --port 9000
Restart=on-failure
RestartSec=3
TimeoutStopSec=30
KillSignal=SIGTERM
LimitNOFILE=65535
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

启用服务：

```bash
systemctl daemon-reload
systemctl enable --now cityflow-sim
systemctl status cityflow-sim --no-pager -l
```

查看日志：

```bash
journalctl -u cityflow-sim -f
```

重启：

```bash
systemctl restart cityflow-sim
```

停止：

```bash
systemctl stop cityflow-sim
```

## 10. 更新云端 CityFlow 代码

本地修改 `sim-python/app` 或 `sim-python/data` 后，只上传精简包，不要上传整个项目。以下命令适用于当前未依赖 Git 提交、直接发布工作区代码的情况。

本地 PowerShell 在项目根目录打包：

```powershell
cd D:\Github\Traffic-Signal-Control-System
tar.exe -czf cityflow-update.tar.gz sim-python/app sim-python/data sim-python/tests sim-python/README.md
```

上传：

```powershell
scp .\cityflow-update.tar.gz root@39.105.75.87:/tmp/
```

云端停止服务、备份、解压并验证：

```bash
systemctl stop cityflow-sim
cd /opt/Traffic-Signal-Control-System
cp -a sim-python "sim-python.backup.$(date +%Y%m%d_%H%M%S)"
tar -xzf /tmp/cityflow-update.tar.gz -C /opt/Traffic-Signal-Control-System
cd /opt/Traffic-Signal-Control-System/sim-python
/root/miniconda3/envs/cityflow39/bin/python -m compileall -q app
/root/miniconda3/envs/cityflow39/bin/python -c "import cityflow; print('CityFlow import OK')"
systemctl daemon-reload
systemctl restart cityflow-sim
systemctl status cityflow-sim --no-pager -l
journalctl -u cityflow-sim -n 100 --no-pager
```

确认新服务稳定后，先查询备份的准确名称，再删除指定备份：

```bash
ls -ld /opt/Traffic-Signal-Control-System/sim-python.backup.*
rm -rf -- /opt/Traffic-Signal-Control-System/sim-python.backup.YYYYMMDD_HHMMSS
```

禁止删除当前运行目录 `/opt/Traffic-Signal-Control-System/sim-python`。

## 11. 多会话与释放验证

连续创建两个会话后，`/health.activeSessions` 应为 `2`。停止其中一个后应降为 `1`：

```bash
TOKEN='当前团队token'

R1=$(curl -s -X POST http://127.0.0.1:9000/cityflow/simulations \
  -H "X-CityFlow-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sceneId":"jinan_3x4","speed":1}')

R2=$(curl -s -X POST http://127.0.0.1:9000/cityflow/simulations \
  -H "X-CityFlow-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sceneId":"jinan_3x4","speed":1}')

echo "$R1"
echo "$R2"
curl -s http://127.0.0.1:9000/health

SID=$(echo "$R1" | /root/miniconda3/envs/cityflow39/bin/python \
  -c "import sys,json; print(json.load(sys.stdin)['sid'])")

curl -X POST "http://127.0.0.1:9000/cityflow/simulations/$SID/stop" \
  -H "X-CityFlow-Token: $TOKEN"

curl -s http://127.0.0.1:9000/health
```

注意：只有 Python 云端更新还不够。Spring Boot 也必须运行包含多会话修改的最新代码，否则旧后端仍可能在创建新仿真前主动停止已有会话。

## 12. 常见问题

### 本地 `Test-NetConnection` 失败

说明公网 TCP 9000 不通。检查：

1. `ss -lntp | grep 9000` 是否监听 `0.0.0.0:9000`。
2. 阿里云安全组是否绑定到当前 ECS 实例。
3. 安全组入方向是否为 `TCP 9000/9000 0.0.0.0/0`。
4. `ufw` 或 `firewalld` 是否拦截。

### 云端 `/health` 成功，但受保护接口 401

说明 token 不一致。云端 `CITYFLOW_API_TOKEN` 必须与 Spring Boot 的 `cityflow.api-token` 一致。

### 后端仍然访问本地 CityFlow

确认后端已重启，并检查是否有环境变量覆盖：

```powershell
echo $env:CITYFLOW_BASE_URL
```

如果环境变量仍是 `http://localhost:9000`，需要清空或改为云端地址。
