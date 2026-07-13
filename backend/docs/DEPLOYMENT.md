# 部署说明

本文档记录当前后端与 Python CityFlow 服务的部署方式、环境边界和云端部署注意事项。后续部署前必须先阅读本文档，避免把本地 WSL 联调方式误当成云端部署方案。

## 1. 当前默认联调方式

当前默认联调方式是：Spring Boot 在本地运行，Python CityFlow 在阿里云 24 小时运行，Traffic-R 在 AutoDL 按需启动。

```text
本地 Spring Boot
  -> HTTP 调用阿里云 Python CityFlow
  -> CityFlow Engine

本地 Spring Boot
  -> SSH 隧道
  -> AutoDL Traffic-R /predict-batch（仅 RL 测试时需要）
```

Spring Boot 不负责启动 Python 进程，也不会进入 WSL、conda 或 AutoDL 环境。Spring Boot 只读取配置项：

```yaml
cityflow:
  base-url: ${CITYFLOW_BASE_URL:http://39.105.75.87:9000}
  api-token: ${CITYFLOW_API_TOKEN:jLEc-o3L16migUKQ7f_OlH94qsjEstFf}
  client-id: ${CITYFLOW_CLIENT_ID:hcj}

traffic-r:
  base-url: http://127.0.0.1:16008
```

然后通过 HTTP 调用 Python 服务：

```http
GET  /cityflow/scenes/{sceneId}/roadnet
POST /cityflow/simulations
GET  /cityflow/simulations/{sid}/frame
POST /cityflow/simulations/{sid}/actions
POST /cityflow/simulations/{sid}/start
POST /cityflow/simulations/{sid}/pause
POST /cityflow/simulations/{sid}/stop
```

当前阿里云 CityFlow 的路径、systemd、启动和更新流程以 `CITYFLOW_CLOUD_RUNBOOK.md` 为准；Traffic-R 的 AutoDL 启动和隧道流程以 `TRAFFIC_R_CLOUD_RUNBOOK.md` 为准。

### 本地 WSL 备用 CityFlow

如果阿里云 CityFlow 不可用，才需要在本地 WSL Ubuntu 中启动真实 CityFlow 模式，并通过环境变量把 Spring Boot 切回本地：

```powershell
$env:CITYFLOW_BASE_URL="http://127.0.0.1:9000"
```

WSL 内手动启动：

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

### 云端 PostgreSQL 与 Flyway

后端使用 Flyway 10 管理数据库结构。Flyway 10 的数据库支持已拆分为独立模块，连接 PostgreSQL 时 `pom.xml` 必须同时包含 `flyway-core` 和 `flyway-database-postgresql`；缺少后者会在数据库连接成功后报 `Unsupported Database: PostgreSQL 16.x`。

项目按 Java 17 构建和测试。本地运行前应确认 `java -version` 与 `mvn -version` 都指向 JDK 17。Windows PowerShell 可以临时切换：

```powershell
$env:JAVA_HOME="C:\Users\<user>\.jdks\ms-17.0.19"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

每次数据库迁移验证前执行干净构建，避免 `target/classes/db/migration` 残留已删除的迁移文件：

```powershell
mvn clean test
mvn spring-boot:run
```

如果共享 PostgreSQL 是通过 SQL 文件导入的非空旧库，且没有 `flyway_schema_history`，应先确认其结构完整包含 V1-V5，再由一名维护者执行一次 baseline：

```powershell
$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE="true"
$env:SPRING_FLYWAY_BASELINE_VERSION="5"
mvn clean spring-boot:run
```

看到 V5 baseline、后续迁移成功以及 `Started TrafficSignalBackendApplication` 后，清理临时变量：

```powershell
Remove-Item Env:SPRING_FLYWAY_BASELINE_ON_MIGRATE
Remove-Item Env:SPRING_FLYWAY_BASELINE_VERSION
```

baseline 只对共享数据库执行一次。其他成员在 `flyway_schema_history` 已存在后直接正常启动，不应重复手工创建迁移记录。

## 2. 云端部署原则

云端部署不能依赖 WSL。WSL 只是 Windows 本地开发环境，不是服务器部署方案。

云端必须满足：

- Python CityFlow 服务运行在 Linux Python 环境中。
- Python 环境中必须能 `import cityflow`。
- Spring Boot 通过 HTTP 调用 Python 服务。
- `cityflow.base-url` 必须指向云端 Python 服务真实地址，不能盲目使用 `localhost`。
- 前端仍然只连接 Spring Boot，不直接连接 Python CityFlow 服务。
- CityFlow 如果开放公网端口，必须启用 `CITYFLOW_API_TOKEN`，Spring Boot 通过 `X-CityFlow-Token` 访问。
- 多人开发时会话统一按 `sid` 区分，`CITYFLOW_CLIENT_ID` 仅为兼容保留，不再影响创建、访问和清理行为。
- 服务支持多个会话并行运行，新建会话不再因旧会话数量达到 `SIM_MAX_ACTIVE_SESSIONS` 而被拒绝；`SIM_MAX_ACTIVE_SESSIONS=0` 表示不设置创建数量上限。显式停止、场景自然结束、idle TTL、abandoned TTL 或最大生存期到达后会自动释放对应 CityFlow Engine；abandoned TTL 专门用于清理 running 但长期没有后端请求的会话。
- `SIM_SESSION_DRAIN_TIMEOUT_SECONDS` 默认 600；最后发车后即使路网因死锁无法清空，到达该仿真时间宽限期也会强制释放会话。
- AutoDL Traffic-R 不需要长期运行；只有选择 `traffic-r` / `rl` 策略做模型测试时才启动。

## 3. 本地通过隧道接入云端 Traffic-R

当前阶段是本地 Spring Boot 通过 SSH 隧道访问 AutoDL 上的 Traffic-R。公网未映射模型服务端口时，本地 Spring Boot 不能直接访问云端 `6008`，必须先在本机打开 SSH 本地端口转发：

```powershell
ssh -p 49328 -L 16008:127.0.0.1:6008 root@connect.westd.seetacloud.com
```

其中：

- `6008` 是云端 Traffic-R 服务监听端口。
- `16008` 是本机转发端口。
- Spring Boot 只访问本机隧道地址 `http://127.0.0.1:16008`。

后端默认配置为：

```yaml
traffic-r:
  enabled: true
  base-url: ${TRAFFIC_R_BASE_URL:http://127.0.0.1:16008}
  health-path: ${TRAFFIC_R_HEALTH_PATH:/health}
  predict-path: ${TRAFFIC_R_PREDICT_PATH:/predict}
  batch-predict-path: ${TRAFFIC_R_BATCH_PREDICT_PATH:/predict-batch}
  decision-interval-sec: ${TRAFFIC_R_DECISION_INTERVAL_SEC:10}
  timeout-sec: ${TRAFFIC_R_TIMEOUT_SEC:30}
  fallback-controller: ${TRAFFIC_R_FALLBACK_CONTROLLER:max-pressure}
```

本地验证隧道和模型接口：

```powershell
Invoke-RestMethod http://127.0.0.1:16008/health
```

批量预测接口验证建议使用 PowerShell 原生 JSON 序列化，避免 `curl` 在 PowerShell 中被别名和引号转义影响：

```powershell
$body = @{
  sceneId = "jinan_3x4"
  simTime = 120.0
  intersections = @(
    @{
      intersectionId = "intersection_1_1"
      currentPhaseIndex = 1
      currentPhaseCode = "ETWT"
      phaseCandidates = @(
        @{ phaseIndex = 1; phaseCode = "ETWT" },
        @{ phaseIndex = 2; phaseCode = "NTST" },
        @{ phaseIndex = 3; phaseCode = "ELWL" },
        @{ phaseIndex = 4; phaseCode = "NLSL" }
      )
    },
    @{
      intersectionId = "intersection_1_2"
      currentPhaseIndex = 2
      currentPhaseCode = "NTST"
      phaseCandidates = @(
        @{ phaseIndex = 1; phaseCode = "ETWT" },
        @{ phaseIndex = 2; phaseCode = "NTST" },
        @{ phaseIndex = 3; phaseCode = "ELWL" },
        @{ phaseIndex = 4; phaseCode = "NLSL" }
      )
    }
  )
  observation = @{
    roads = @(
      @{ id = "road_1_1_0"; queueCount = 8; vehicleCount = 12 },
      @{ id = "road_1_1_1"; queueCount = 2; vehicleCount = 4 }
    )
    metrics = @{
      queueCount = 10
    }
  }
} | ConvertTo-Json -Depth 10

Invoke-RestMethod `
  -Uri "http://127.0.0.1:16008/predict-batch" `
  -Method Post `
  -ContentType "application/json" `
  -Body $body
```

Traffic-R 在线批量推理当前测试平均约 7 秒，不能按 `cityflow.frame-poll-interval-ms=100` 每帧调用。后端策略调度必须按 `traffic-r.decision-interval-sec` 做低频决策，当前联调默认 10 秒仿真时间每个仿真会话最多调用一次 `/predict-batch`；推理完成后一次性下发所有路口决策。若连续 3 次无效、超时或请求失败，后端自动启用 Max-Pressure fallback，连续 3 次有效后恢复 RL。

注意：`http://127.0.0.1:16008` 只适用于“本地 Windows 通过 SSH 隧道访问云端模型”的联调场景。后续如果 Spring Boot、Python CityFlow、Traffic-R 模型都部署在同一台云服务器上，不能继续使用 `16008` 隧道端口。

### Agent LLM API Key 调试配置（2026-07-12）

当前 `/api/v1/agent/chat` 已切换为后端自建 Agent 编排流程：前端只调用 Spring Boot，Spring Boot 使用 LangChain4j 的 OpenAI-compatible `ChatModel` 调用模型 API Key；不再在编排层自动 fallback 到百炼平台 Agent 应用 API。

本地测试建议在 `.env` 中配置：

```properties
AGENT_LANGCHAIN4J_ENABLED=true
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen-plus
LLM_API_KEY=你的模型APIKey
AGENT_MODEL_TEMPERATURE=0.2
AGENT_MODEL_TIMEOUT_SECONDS=60
AGENT_MODEL_ENABLE_THINKING=false
```

兼容说明：

- `LLM_API_KEY` 优先级高于 `DASHSCOPE_API_KEY`；`LLM_BASE_URL` / `LLM_MODEL` 优先级高于 `DASHSCOPE_COMPATIBLE_BASE_URL` / `DASHSCOPE_MODEL`。
- 百炼 Qwen3 非流式调用要求 `enable_thinking=false`，当前通过 `AGENT_MODEL_ENABLE_THINKING=false` 传入 OpenAI-compatible 自定义参数；如果后续改为流式或更换模型，再按模型要求调整。
- 旧百炼 Agent 应用代理代码已删除；`/api/v1/agent/chat` 只走后端自建 Agent 编排层。百炼仅作为模型 OpenAI-compatible 调用方和知识库 `Retrieve` 数据源使用。
- 如果未配置 key，接口会返回 `Agent LLM is not configured...`；如果 key、base-url 或模型名错误，会返回 `Agent LLM call failed...`，并在后端 `AGENT_DEBUG` 日志中记录异常。
- 临时调试日志 logger 为 `AGENT_DEBUG`，会记录 `agent.chat.start/end/error`、`agent.llm.request/response/error`、`agent.tool.start/result/error`。日志会截断长文本并脱敏 key/token/password/authorization 字段，但仍可能包含用户问题、工具参数、工具结果和模型返回，联调结束后应降低日志级别。
- 后端日志默认写入 `logs/traffic-signal-backend.log`，也可以通过 `BACKEND_LOG_FILE` 覆盖。默认从 `backend` 目录启动 Spring Boot 时，可用 `Get-Content .\logs\traffic-signal-backend.log -Wait -Tail 200` 实时查看；日志目录已在 `.gitignore` 中忽略，不应提交。

### Agent LangChain4j / 百炼模型配置

当前后端已完成 LangChain4j 依赖配置、Agent 编排层和第一批 `@Tool` 工具封装，默认通过 OpenAI-compatible LLM API Key 调用模型：

```yaml
traffic:
  agent:
    langchain4j:
      enabled: ${AGENT_LANGCHAIN4J_ENABLED:true}
      base-url: ${LLM_BASE_URL:${DASHSCOPE_COMPATIBLE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}}
      api-key: ${LLM_API_KEY:${DASHSCOPE_API_KEY:}}
      model-name: ${LLM_MODEL:${DASHSCOPE_MODEL:qwen-plus}}
      temperature: ${AGENT_MODEL_TEMPERATURE:0.2}
      timeout-seconds: ${AGENT_MODEL_TIMEOUT_SECONDS:60}
      enable-thinking: ${AGENT_MODEL_ENABLE_THINKING:false}
```

部署注意：

- 当前不再保留旧 `BailianAgentService`，也不会自动 fallback 到百炼平台 Agent 应用。`bailian.knowledge.*` 仅用于百炼知识库 `Retrieve`，LLM 模型调用统一使用 `traffic.agent.langchain4j.*`。
- `AGENT_LANGCHAIN4J_ENABLED` 默认是 `true`。需要确保 `LLM_API_KEY` 或 `DASHSCOPE_API_KEY` 已配置；如果临时关闭该开关，`/api/v1/agent/chat` 会返回模型未配置错误。
- 不引入 `langchain4j-spring-boot-starter`，不要求升级 Spring Boot。
- `LLM_API_KEY` / `DASHSCOPE_API_KEY` 不应写入文档、日志或 Git；本地联调建议写入 `.env`，稳定后应改为环境变量或部署密钥。
- Agent 工具层位于 `com.traffic.agent.tool`，只读工具只能调用后端 Service。实时交通状态工具读取 Spring Boot 内存中的 `LiveSimulationStateService` 最近帧缓存；历史复盘、决策、推理、fallback、告警和审计工具读取 PostgreSQL。模型不能凭空生成实时状态；实时缓存为空时必须返回“无法获取实时状态”。

## 4. 后续同机部署方案

后续验收或演示如果把 Spring Boot、Python CityFlow、Traffic-R 模型和 PostgreSQL 都部署在同一台 Linux 云服务器上，推荐采用“四个独立进程 + 仅 Spring Boot 对外暴露”的方式：

```text
Linux 云服务器
|-- Spring Boot:       0.0.0.0:8080   对前端开放
|-- Python CityFlow:   127.0.0.1:9000 仅 Spring Boot 访问
|-- Traffic-R Service: 127.0.0.1:6008 仅 Spring Boot 访问
`-- PostgreSQL:        127.0.0.1:5432 仅后端访问
```

Spring Boot 配置应改为服务器内本机地址：

```yaml
cityflow:
  base-url: ${CITYFLOW_BASE_URL:http://127.0.0.1:9000}

traffic-r:
  enabled: true
  base-url: ${TRAFFIC_R_BASE_URL:http://127.0.0.1:6008}
  health-path: /health
  predict-path: /predict
  batch-predict-path: /predict-batch
  decision-interval-sec: 10
  timeout-sec: 30
  fallback-controller: max-pressure
```

推荐启动顺序：

1. 启动 PostgreSQL，并确认后端数据库账号可连接。
2. 启动 Python CityFlow 服务，监听 `127.0.0.1:9000` 或服务器内网地址。
3. 启动 Traffic-R 服务，监听 `127.0.0.1:6008`。
4. 在服务器本机验证 `curl http://127.0.0.1:9000/health`。
5. 在服务器本机验证 `curl http://127.0.0.1:6008/health` 和 `POST /predict-batch`。
6. 启动 Spring Boot，并通过环境变量覆盖：

```sh
export CITYFLOW_BASE_URL=http://127.0.0.1:9000
export TRAFFIC_R_BASE_URL=http://127.0.0.1:6008
java -jar traffic-signal-backend.jar
```

同机部署时不需要 SSH `-L` 本地端口转发，也不需要把 Traffic-R 的 `6008` 暴露到公网。公网只需要开放 Spring Boot 或 Nginx 网关端口；Python CityFlow 和 Traffic-R 保持服务器内部访问即可。

如果后续使用 Docker Compose，同一规则仍然成立，但容器内 `127.0.0.1` 只代表当前容器本身，Spring Boot 容器访问其他容器时必须使用服务名：

```yaml
cityflow:
  base-url: http://sim-python:9000

traffic-r:
  base-url: http://traffic-r:6008
```

## 5. 其他推荐部署方案

### 方案 A：同一台 Linux 服务器

适合实训验收和小规模演示，配置简单；如果接入 Traffic-R，应优先采用上一节“四个独立进程”的同机方案。

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

## 6. 不推荐方案

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

同样不要让 Spring Boot 通过命令行启动 Traffic-R 模型服务：

```text
Spring Boot -> Runtime.exec("python traffic_r_service.py ...")
```

Traffic-R 推理进程启动慢、依赖 GPU 和虚拟环境，必须作为独立服务管理。后续可用 `systemd`、`tmux`、`supervisor` 或容器编排托管进程生命周期。

## 7. 部署前检查清单

部署前必须逐项确认：

| 检查项 | 命令或方式 | 通过标准 |
|---|---|---|
| Python 能导入 CityFlow | `python -c "import cityflow; print(cityflow.__version__)"` | 输出版本号 |
| Python 服务启动 | `curl http://127.0.0.1:9000/health` | `engineMode=cityflow` |
| Spring Boot 能访问 Python | 创建仿真接口 | 返回 `sid` |
| Spring Boot 能拉取 frame | 启动仿真后观察 WebSocket | 收到 `sim.frame` |
| CityFlow lane-level 状态可用 | 检查 `sim.frame.data.laneStates` | 每个真实路口包含 `WT/WL/ST/SL/ET/EL/NT/NL` |
| Traffic-R 隧道可用 | `Invoke-RestMethod http://127.0.0.1:16008/health` | 返回健康状态 |
| Traffic-R 批量预测可用 | 按上文示例 POST `/predict-batch` | 返回 `decisions` 列表 |
| 同机 Traffic-R 配置 | `TRAFFIC_R_BASE_URL=http://127.0.0.1:6008` | 不再使用本地隧道端口 `16008` |
| 前端不直连 Python | 检查前端配置 | 只访问 Spring Boot |
| 数据库可用 | Spring Boot 启动日志 / Flyway | 迁移成功 |
| 认证邮件配置 | `QQ_MAIL_USERNAME`、`QQ_MAIL_AUTH_CODE`、`QQ_MAIL_FROM` | 验证码邮件可发送，密钥不进入 Git |
| 初始账号与邀请码 | `AUTH_INITIAL_PASSWORD`、`AUTH_INVITE_CODE` | 公网或演示环境不得继续使用默认 `123456` |

## 8. 当前已知部署风险

- 当前 Python 服务仍使用 `ThreadingHTTPServer`，适合实训联调，不适合长期生产运行。
- 当前仿真会话保存在 Python 内存中，Python 服务重启后会话丢失。
- 当前 Spring Boot 尚未主动调用 `/health` 做 Python 服务启动前检查。
- 当前认证模块返回的 `token` 是临时 UUID 标识，尚未接入 JWT、服务端会话校验或全局鉴权拦截器；不能把它视为生产级权限边界。
- Windows 中文用户目录可能导致 Maven/Spring Boot 插件运行 classpath 转码异常；本项目已在 `backend/.mvn/maven.config` 中固定本地 Maven 仓库和临时目录，开发启动时应先 `cd backend` 再执行 `mvn spring-boot:run`。
- 云端如果拆分部署 Spring Boot 和 Python，必须修改 `cityflow.base-url`，不能继续使用本地默认值。
- 云端 Traffic-R 如果未做公网端口映射，必须保持 SSH 隧道进程运行；后端默认 `traffic-r.base-url` 只代表本机隧道地址，不代表 AutoDL 公网可直连。
- 后续三服务同机部署时，`traffic-r.base-url` 应切换为服务器内服务地址 `http://127.0.0.1:6008`；`http://127.0.0.1:16008` 只代表本地 Windows SSH 隧道。

## 9. 运行手册入口

- CityFlow 阿里云 24h 服务路径、启动、验证与更新流程见 `CITYFLOW_CLOUD_RUNBOOK.md`。
- Traffic-R / AutoDL 云端模型启动、SSH 隧道、本地验证与后端接入流程见 `TRAFFIC_R_CLOUD_RUNBOOK.md`。
