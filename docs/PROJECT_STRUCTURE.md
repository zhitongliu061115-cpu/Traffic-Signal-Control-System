# 项目目录架构

## 总览

```text
Traffic-Signal-Control-System/
├─ sys-frontend/
│  ├─ src/
│  │  ├─ api/                 # REST / WebSocket 客户端封装
│  │  ├─ components/traffic/  # 路网、车辆、信号灯、指标组件
│  │  ├─ composables/         # 前端复用逻辑，如实时订阅
│  │  ├─ router/              # 页面路由
│  │  ├─ stores/              # Pinia 状态管理
│  │  ├─ styles/              # 全局样式与大屏主题
│  │  ├─ types/               # 前端共享类型
│  │  └─ views/               # 大屏页面与调度页面
│  └─ package.json
├─ backend/
│  ├─ app/
│  │  ├─ api/v1/              # REST 与 WebSocket 路由
│  │  ├─ agent/               # 调度智能体与 RAG 问答
│  │  ├─ core/                # 配置、日志、协议、通用工具
│  │  ├─ emergency/           # 应急绿波策略
│  │  ├─ schemas/             # Pydantic 数据模型
│  │  ├─ services/            # 跨模块业务编排
│  │  ├─ signal/              # AI 自适应信号控制
│  │  ├─ sim/                 # 交通仿真与数据生成
│  │  └─ main.py              # FastAPI 应用入口
│  ├─ tests/                  # 后端测试
│  └─ requirements.txt
├─ docs/
│  ├─ API_GUIDELINES.md
│  ├─ GIT_GUIDELINES.md
│  ├─ VIBE_CODING_GUIDELINES.md
│  ├─ PROJECT_STRUCTURE.md
│  └─ PROMPTS.md
├─ .gitignore
└─ README.md
```

## 模块边界

- 前端只通过 `docs/API_GUIDELINES.md` 约定的 REST 和 WebSocket 与后端通信。
- 后端 `api/v1` 只负责协议入口，具体业务逻辑放到 `services` 和各领域模块。
- `signal` 负责普通交通流下的自适应控制；`emergency` 负责更高优先级的应急绿波。
- `agent` 可读取交通状态和知识库，输出自然语言解释与建议动作，但核心调度动作仍应经过后端服务校验。
- `sim` 先提供演示/仿真数据，后续可替换或对接 SUMO，不影响前端接口。

## 阶段建议

1. `v0.1`: 路网、车辆、信号灯与指标大屏跑通。
2. `v0.2`: 接入自适应信号控制策略。
3. `v0.3`: 接入应急车辆检测和绿波控制。
4. `v0.4`: 接入调度智能体与 RAG 问答。
5. `v1.0`: 集成演示、测试、文档与答辩材料。
