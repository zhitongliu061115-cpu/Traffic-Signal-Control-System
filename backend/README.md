# backend

后端服务负责实时交通状态、AI 自适应信号控制、应急绿波、智能体问答和 WebSocket 推送。

## 技术基线

- Python
- FastAPI
- WebSocket
- Pydantic

## 本地启动

```sh
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

健康检查：

```text
GET /api/v1/health
```

## 模块说明

- `app/api/v1`: REST 和 WebSocket 路由入口。
- `app/core`: 配置、日志、协议、公共工具。
- `app/schemas`: 接口数据模型。
- `app/services`: 跨模块业务编排。
- `app/sim`: 路网和车流仿真。
- `app/signal`: AI 自适应信号控制。
- `app/emergency`: 应急绿波控制。
- `app/agent`: 自然语言调度智能体与 RAG。
