# 文档目录说明

本目录用于保存项目技术文档、接口规范、架构说明和团队协作材料。所有成员在新增模块、修改接口或调整数据结构前，应先阅读对应文档，避免前后端、算法服务和数据库设计脱节。

## 公共文档

- `PROJECT_STRUCTURE.md`：项目整体目录结构和模块边界。
- `CFRP-1.0-前后端通信协议.md`：前后端实时仿真通信协议。
- `GIT_GUIDELINES.md`：Git 分支、提交和协作规范。
- `PROMPTS.md`：团队可复用 Prompt 和 AI 协作提示词。

## 后端文档

后端相关文档已移动到 `backend/docs/`，避免和公共文档混淆：

- `backend/docs/API_GUIDELINES.md`：REST / WebSocket / Python 服务接口协作规范。
- `backend/docs/BACKEND_ARCHITECTURE.md`：Spring Boot 主后端架构、包职责、依赖方向和扩展规则。
- `backend/docs/CALL_CHAIN.md`：阶段 2 固定调用链。
- `backend/docs/TECHNICAL_DESIGN.md`：后端技术设计、数据流和数据库原则。

## 文档维护规则

1. 接口字段变化时，必须同步更新 `backend/docs/API_GUIDELINES.md` 和 CFRP 协议文档。
2. 包结构或模块职责变化时，必须同步更新 `PROJECT_STRUCTURE.md` 和 `backend/docs/BACKEND_ARCHITECTURE.md`。
3. 数据库表结构变化时，必须新增 Flyway 迁移脚本，并在 `backend/docs/TECHNICAL_DESIGN.md` 中说明。
4. 文档优先使用中文，术语首次出现时给出简短解释。
