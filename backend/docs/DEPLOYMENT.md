# 部署说明

本文档记录当前后端与 Python CityFlow 服务的部署方式、环境边界和云端部署注意事项。后续部署前必须先阅读本文档，避免把本地 WSL 联调方式误当成云端部署方案。

## 1. 当前本地联调方式

当前本地联调采用两个独立进程：

```text
Spring Boot 主后端
  -> HTTP 调用
Python CityFlow 服务
  -> 调用
CityFlow Engine
```

Spring Boot 不负责启动 Python 进程，也不会进入 WSL 或 conda 环境。Spring Boot 只读取配置项：

```yaml
cityflow:
  base-url: http://localhost:9000
```

然后通过 HTTP 调用 Python 服务：

```http
GET  /cityflow/scenes/{sceneId}/roadnet
POST /cityflow/simulations
GET  /cityflow/simulations/{sid}/frame
```

本地真实 CityFlow 模式需要手动在 WSL Ubuntu 中启动：

```sh
cd /mnt/d/Github/Traffic-Signal-Control-System/sim-python
conda activate traffic-rl
SIM_ENGINE_MODE=cityflow python app/server.py --host 0.0.0.0 --port 9000
```

为了避免每次手动进入 WSL、激活 conda、切换目录，可以在 Windows PowerShell 中使用启动脚本：

```powershell
.\scripts\start-cityflow-wsl.ps1
```

注意：该脚本需要在你自己的 Windows 用户 PowerShell 中运行。如果在 Codex 沙箱用户或其他 Windows 用户下运行，可能看不到你安装的 WSL `Ubuntu` 发行版。

脚本会完成：

1. 检查 `http://127.0.0.1:9000/health` 是否已有 `cityflow` 模式服务。
2. 将当前仓库路径转换为 WSL 路径。
3. 进入 WSL Ubuntu。
4. 激活 conda 环境 `traffic-rl`。
5. 由 Windows 启动一个隐藏的 `wsl.exe` 进程，并在其中以前台方式运行 Python CityFlow 服务。
6. 再次访问 `/health` 确认服务可用。

服务日志位于：

```text
sim-python/logs/cityflow-service.log
```

停止本地 WSL Python 服务：

```powershell
.\scripts\stop-cityflow-wsl.ps1
```

默认会使用 `wsl -l -v` 中带星号的默认发行版。如果你的 WSL 发行版名或 conda 环境名不同，可以传参：

```powershell
.\scripts\start-cityflow-wsl.ps1 -Distro Ubuntu -CondaEnv traffic-rl -Port 9000
```

确认 Python 服务：

```sh
curl http://127.0.0.1:9000/health
```

返回中必须看到：

```json
{
  "engineMode": "cityflow"
}
```

## 2. 云端部署原则

云端部署不能依赖 WSL。WSL 只是 Windows 本地开发环境，不是服务器部署方案。

云端必须满足：

- Python CityFlow 服务运行在 Linux Python 环境中。
- Python 环境中必须能 `import cityflow`。
- Spring Boot 通过 HTTP 调用 Python 服务。
- `cityflow.base-url` 必须指向云端 Python 服务真实地址，不能盲目使用 `localhost`。
- 前端仍然只连接 Spring Boot，不直接连接 Python CityFlow 服务。

## 3. 推荐部署方案

### 方案 A：同一台 Linux 服务器

适合实训验收和小规模演示，配置简单。

```text
Linux 云服务器
|-- Spring Boot: 8080
|-- Python CityFlow: 9000
`-- PostgreSQL: 5432
```

Spring Boot 配置：

```yaml
cityflow:
  base-url: http://127.0.0.1:9000
```

启动顺序：

1. 启动 PostgreSQL。
2. 启动 Python CityFlow 服务。
3. 访问 `/health` 确认 `engineMode=cityflow`。
4. 启动 Spring Boot。
5. 前端连接 Spring Boot。

### 方案 B：Docker Compose 双服务

适合后续规范化部署。

```text
docker-compose
|-- backend      Spring Boot
|-- sim-python   Python CityFlow
`-- postgres     PostgreSQL
```

Spring Boot 配置：

```yaml
cityflow:
  base-url: http://sim-python:9000
```

注意：容器内的 `localhost` 只代表当前容器本身。Spring Boot 容器访问 Python 容器时，不能写 `http://localhost:9000`，应使用 Compose 服务名 `sim-python`。

## 4. 不推荐方案

不要让 Spring Boot 通过命令行启动 WSL、conda 或 Python：

```text
Spring Boot -> Runtime.exec("wsl ... python app/server.py")
```

原因：

- 云端 Linux 没有 WSL。
- 进程生命周期难管理。
- 日志、重启、异常恢复复杂。
- 权限和环境变量容易出错。
- 不利于后续容器化和 CI/CD。

## 5. 部署前检查清单

部署前必须逐项确认：

| 检查项 | 命令或方式 | 通过标准 |
|---|---|---|
| Python 能导入 CityFlow | `python -c "import cityflow; print(cityflow.__version__)"` | 输出版本号 |
| Python 服务启动 | `curl http://127.0.0.1:9000/health` | `engineMode=cityflow` |
| Spring Boot 能访问 Python | 创建仿真接口 | 返回 `sid` |
| Spring Boot 能拉取 frame | 启动仿真后观察 WebSocket | 收到 `sim.frame` |
| 前端不直连 Python | 检查前端配置 | 只访问 Spring Boot |
| 数据库可用 | Spring Boot 启动日志 / Flyway | 迁移成功 |

## 6. 当前已知部署风险

- 当前 Python 服务仍使用 `ThreadingHTTPServer`，适合实训联调，不适合长期生产运行。
- 当前仿真会话保存在 Python 内存中，Python 服务重启后会话丢失。
- 当前 Spring Boot 尚未主动调用 `/health` 做 Python 服务启动前检查。
- Windows 中文用户目录可能导致 Maven/Spring Boot 插件运行 classpath 转码异常；本项目已在 `backend/.mvn/maven.config` 中固定本地 Maven 仓库和临时目录，开发启动时应先 `cd backend` 再执行 `mvn spring-boot:run`。
- 云端如果拆分部署 Spring Boot 和 Python，必须修改 `cityflow.base-url`，不能继续使用本地默认值。
