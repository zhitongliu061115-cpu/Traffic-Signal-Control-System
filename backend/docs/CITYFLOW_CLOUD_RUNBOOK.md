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
| Spring Boot 默认 client id | `hcj` |
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

## 2. 云端 Python 环境

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

## 3. 手动启动 CityFlow 服务

进入云端服务目录：

```bash
cd /opt/Traffic-Signal-Control-System/sim-python
conda activate cityflow39
```

启动真实 CityFlow 模式：

```bash
SIM_ENGINE_MODE=cityflow \
CITYFLOW_API_TOKEN="jLEc-o3L16migUKQ7f_OlH94qsjEstFf" \
SIM_MAX_ACTIVE_SESSIONS=4 \
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
- `SIM_MAX_ACTIVE_SESSIONS=4` 是为 4 核 8G 服务器设置的资源上限。

## 4. 云端本机验证

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

## 5. 本地公网验证

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

## 6. 阿里云安全组与防火墙

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

## 7. Spring Boot 当前配置

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

如果某个成员要使用自己的 client id，可以在启动前覆盖：

```powershell
$env:CITYFLOW_CLIENT_ID="your-name"
```

## 8. systemd 方式 24 小时运行

创建服务文件：

```bash
vim /etc/systemd/system/cityflow-sim.service
```

写入：

```ini
[Unit]
Description=Traffic Signal CityFlow Simulation Service
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/Traffic-Signal-Control-System/sim-python
Environment=SIM_ENGINE_MODE=cityflow
Environment=CITYFLOW_API_TOKEN=jLEc-o3L16migUKQ7f_OlH94qsjEstFf
Environment=SIM_MAX_ACTIVE_SESSIONS=4
Environment=SIM_MAX_SPEED=10
Environment=SIM_VISIBLE_VEHICLE_LIMIT=300
Environment=SIM_MAX_REQUEST_BYTES=1048576
Environment=SIM_REALTIME_TICK_SECONDS=0.1
Environment=SIM_MIN_REALTIME_TICK_SECONDS=0.02
ExecStart=/root/miniconda3/envs/cityflow39/bin/python app/server.py --host 0.0.0.0 --port 9000
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

启用服务：

```bash
systemctl daemon-reload
systemctl enable --now cityflow-sim
systemctl status cityflow-sim
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

## 9. 更新云端 CityFlow 代码

本地修改 `sim-python/app` 或 `sim-python/data` 后，只需要上传精简包，不要上传整个项目。

本地 PowerShell 打包：

```powershell
$src = "D:\Github\Traffic-Signal-Control-System"
$pkg = "$env:TEMP\cityflow-deploy"
$zip = "$env:TEMP\cityflow-deploy.zip"

Remove-Item -Recurse -Force $pkg, $zip -ErrorAction SilentlyContinue

New-Item -ItemType Directory -Force "$pkg\sim-python" | Out-Null
Copy-Item -Recurse "$src\sim-python\app" "$pkg\sim-python\app"
Copy-Item -Recurse "$src\sim-python\data" "$pkg\sim-python\data"
Copy-Item "$src\sim-python\README.md" "$pkg\sim-python\README.md"

Compress-Archive -Path "$pkg\*" -DestinationPath $zip -Force
```

上传：

```powershell
scp "$env:TEMP\cityflow-deploy.zip" root@39.105.75.87:/root/
```

云端解压并重启：

```bash
mkdir -p /opt/Traffic-Signal-Control-System
unzip -o /root/cityflow-deploy.zip -d /opt/Traffic-Signal-Control-System
systemctl restart cityflow-sim
```

如果没有使用 systemd，而是手动启动，则需要先停止原 Python 进程，再重新执行手动启动命令。

## 10. 常见问题

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
