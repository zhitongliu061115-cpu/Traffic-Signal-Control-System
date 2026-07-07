# Traffic-Signal-Control-System

AI 自适应信号控制与应急绿波数字孪生系统。

本项目采用前后端同仓结构：前端负责交通数据分析大屏与数字孪生可视化，后端负责实时仿真数据、AI 信号控制、应急绿波、智能体问答与 WebSocket 推送。

## 目录

```text
Traffic-Signal-Control-System/
├─ sys-frontend/          # Vue + TypeScript 前端大屏
├─ backend/               # Python + FastAPI 后端服务
├─ docs/                  # 项目规范、架构说明、协作资料
└─ README.md
```

## 核心模块

- `dashboard`: 交通数据分析大屏、车辆流动、拥堵热力、应急路径高亮。
- `sim`: 路网、车辆、交通状态仿真数据源，可后续对接 SUMO。
- `signal`: AI 自适应信号控制，决定下一相位和绿灯时长。
- `emergency`: 应急绿波控制，沿应急车辆路径依次放行。
- `agent`: 自然语言调度智能体与交通知识库 RAG 问答。
- `core`: 公共配置、消息协议、模型与工具能力。

## 文档入口

- [项目结构](docs/PROJECT_STRUCTURE.md)
- [接口规范](docs/API_GUIDELINES.md)
- [Git 规范](docs/GIT_GUIDELINES.md)
- [Vibe Coding 规范](docs/VIBE_CODING_GUIDELINES.md)
- [团队 Prompt 库](docs/PROMPTS.md)

## 启动建议

前端：

```sh
cd sys-frontend
npm install
npm run dev
```

后端：

```sh
cd backend
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```
