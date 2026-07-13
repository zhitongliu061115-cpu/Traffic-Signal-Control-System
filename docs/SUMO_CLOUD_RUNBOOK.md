# SUMO 云端部署与故障恢复手册

## 1. 当前已验证环境

本手册记录 2026-07-14 已完成并通过 `/health` 验证的 SUMO 云端部署。

| 项目 | 当前值 |
| --- | --- |
| 云端服务地址 | `http://39.105.75.87:9001` |
| systemd 服务 | `sumo-sim.service` |
| 服务工作目录 | `/opt/Traffic-Signal-Control-System/sim-sumo` |
| HTTP 服务入口 | `app/server.py` |
| Conda 环境 | `/root/miniconda3/envs/sumo127` |
| Python | Conda 环境 Python 3.11 |
| SUMO | Eclipse SUMO 1.27.1 |
| SUMO 二进制 | `/root/miniconda3/envs/sumo127/bin/sumo` |
| 服务端口 | `9001` |
| 当前场景 | `xian_5x5` |
| 调度分区 | 中央 9 个 Traffic-R，外围 16 个 Max-Pressure |

Ubuntu Jammy 自带的 `/usr/bin/sumo` 仍为 1.12.0，不能作为本项目云端服务的实际运行版本。systemd 必须使用 `sumo127` Conda 环境中的 Python 和 SUMO 二进制。

## 2. 基础 systemd 单元

基础单元位于：

```text
/etc/systemd/system/sumo-sim.service
```

当前基础配置保留以下职责：

```ini
[Unit]
Description=Traffic Signal SUMO Simulation Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/Traffic-Signal-Control-System/sim-sumo
EnvironmentFile=/etc/traffic-signal/sumo.env
Restart=on-failure
RestartSec=3
TimeoutStopSec=30
KillMode=control-group
LimitNOFILE=65535
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

`/etc/traffic-signal/sumo.env` 保存部署环境变量和服务鉴权配置。禁止将其中的 API Token、密码或其他密钥提交到 Git。

## 3. SUMO 1.27.1 覆盖配置

使用 systemd drop-in 覆盖 Python 和 SUMO 路径，不直接反复修改基础单元：

```bash
systemctl stop sumo-sim
mkdir -p /etc/systemd/system/sumo-sim.service.d
cat > /etc/systemd/system/sumo-sim.service.d/override.conf <<'EOF'
[Service]
WorkingDirectory=/opt/Traffic-Signal-Control-System/sim-sumo

Environment="SIM_ENGINE_MODE=sumo"
Environment="SIM_DEFAULT_SCENE_ID=xian_5x5"
Environment="SUMO_HOME=/root/miniconda3/envs/sumo127"
Environment="PYTHONPATH=/root/miniconda3/envs/sumo127/lib/python3.11/site-packages/sumo/tools"
Environment="SUMO_BINARY=/root/miniconda3/envs/sumo127/bin/sumo"
Environment="SUMO_GUI_BINARY=/root/miniconda3/envs/sumo127/bin/sumo-gui"
Environment="SUMO_NETCONVERT_BINARY=/root/miniconda3/envs/sumo127/bin/netconvert"
Environment="SUMO_STEP_LENGTH=0.2"

ExecStart=
ExecStart=/root/miniconda3/envs/sumo127/bin/python /opt/Traffic-Signal-Control-System/sim-sumo/app/server.py --host 0.0.0.0 --port 9001
EOF
```

空的 `ExecStart=` 用于清除基础单元中的旧启动命令，不能省略。

加载并启动：

```bash
systemctl daemon-reload
systemctl reset-failed sumo-sim
systemctl restart sumo-sim
sleep 3
systemctl status sumo-sim --no-pager -l
```

确认 systemd 使用了覆盖配置：

```bash
systemctl show sumo-sim -p DropInPaths -p ExecStart -p Environment
```

`ExecStart` 必须包含 `/root/miniconda3/envs/sumo127/bin/python`，不能是 `/usr/bin/python3`。

## 4. 版本与 Python 绑定验证

```bash
/root/miniconda3/envs/sumo127/bin/sumo --version

/root/miniconda3/envs/sumo127/bin/python -c \
"import sumolib,traci; print(sumolib.__file__); print(traci.__file__)"

ls /root/miniconda3/envs/sumo127/bin/{sumo,sumo-gui,netconvert}
```

预期 SUMO 版本为 `1.27.1`，`sumolib` 和 `traci` 必须来自 `sumo127` Conda 环境。

## 5. 健康检查

云服务器本机检查：

```bash
curl -v --max-time 5 http://127.0.0.1:9001/health
```

开发机检查：

```powershell
Invoke-WebRequest -UseBasicParsing -Uri 'http://39.105.75.87:9001/health' -TimeoutSec 5
```

2026-07-14 验证结果：

- HTTP 状态为 `200 OK`；
- `engineMode` 为 `sumo`；
- `sumoBinary` 为 `/root/miniconda3/envs/sumo127/bin/sumo`；
- `sceneIds` 包含 `xian_5x5`；
- `strategyCounts.xian_5x5.trafficR` 为 9；
- `strategyCounts.xian_5x5.maxPressure` 为 16。

检查真实进程环境：

```bash
PID=$(systemctl show -p MainPID --value sumo-sim)
readlink -f /proc/$PID/exe
tr '\0' '\n' < /proc/$PID/environ | grep -E 'SUMO|PYTHON'
```

如果 `MainPID` 为 0，说明服务已经退出，应先查看 journal，而不是继续读取 `/proc/0`。

## 6. Spring Boot 后端接入

前端不能直接连接 SUMO 服务。调用链保持为：

```text
浏览器 -> Spring Boot :8080 -> SUMO HTTP 服务 :9001
```

当 Spring Boot 在开发机运行、SUMO 在云服务器运行时，后端必须配置：

```text
CITYFLOW_BASE_URL=http://39.105.75.87:9001
```

PowerShell 启动示例：

```powershell
$env:CITYFLOW_BASE_URL = 'http://39.105.75.87:9001'
cd D:\Github\Traffic-Signal-Control-System\backend
mvn spring-boot:run
```

IDEA 启动时，应在 Spring Boot Run Configuration 中设置相同的环境变量，然后完整重启后端。

如果 Spring Boot 与 SUMO 部署在同一台云服务器，才使用：

```text
CITYFLOW_BASE_URL=http://127.0.0.1:9001
```

地址必须包含主机和端口之间的冒号。`http://127.0.0.19001` 是非法 URI。

`CITYFLOW_API_TOKEN` 必须与 `/etc/traffic-signal/sumo.env` 中的服务端配置一致，但文档和 Git 中不得记录真实 Token。

## 7. 故障排查

### 7.1 `ModuleNotFoundError: No module named 'sumolib'`

原因：systemd 使用了 `/usr/bin/python3`，绕过了安装 SUMO 1.27.1 的 Conda 环境。

检查：

```bash
systemctl status sumo-sim --no-pager -l
systemctl show sumo-sim -p ExecStart -p Environment
journalctl -u sumo-sim -n 100 --no-pager -l
```

处理：确认 drop-in 已加载，`ExecStart` 使用 `sumo127/bin/python`，然后执行 `systemctl daemon-reload` 和 `systemctl restart sumo-sim`。

### 7.2 systemd 显示短暂 `active (running)`，随后失败

`systemctl status` 可能在 Python 尚未退出的几十毫秒内显示 running。等待 3 秒后再次检查，并以 journal、`MainPID` 和 `/health` 为准。

### 7.3 后端报告 `unsupported URI http://127.0.0.19001/...`

原因：`CITYFLOW_BASE_URL` 缺少端口冒号，Java 在发送 HTTP 请求前就拒绝了 URI。

开发机后端应改为：

```text
http://39.105.75.87:9001
```

同机部署后端才改为：

```text
http://127.0.0.1:9001
```

### 7.4 `/health` 成功但仍无法创建仿真

依次检查：

1. Spring Boot 实际读取的 `CITYFLOW_BASE_URL`；
2. 后端与服务端 `CITYFLOW_API_TOKEN` 是否一致；
3. `journalctl -u sumo-sim` 中是否出现 TraCI 或路网加载异常；
4. 阿里云安全组和服务器防火墙是否允许需要的访问来源连接 9001；
5. `xian_5x5` 场景文件是否与当前服务代码同步。

## 8. 安全要求

- 禁止在文档、Git、日志截图或聊天中记录 root 密码和真实 API Token；
- root 密码一旦暴露应立即执行 `passwd root` 更换；
- 生产环境应使用 SSH 密钥并关闭 root 密码登录；
- 9001 是后端内部依赖端口，正式部署应通过安全组限制来源 IP，不能长期向整个公网开放；
- 更新 SUMO、Conda 环境或 systemd 后，必须重新执行版本检查、`/health` 和一次真实仿真创建测试。
