# 后端文档目录

本目录只保存 Spring Boot 主后端相关文档，避免和项目公共文档混淆。

## 文档列表

- `API_GUIDELINES.md`：后端 REST / WebSocket 接口规范，以及 Spring Boot 调用 Python CityFlow 服务的接口约定。
- `BACKEND_ARCHITECTURE.md`：后端包结构、依赖方向、模块职责和扩展规则。
- `CALL_CHAIN.md`：阶段 2 固定调用链，明确前端、Spring Boot、Python CityFlow 服务和 WebSocket 的边界。
- `DATABASE_CONNECTION.md`：PostgreSQL 连接方式、环境变量和现有数据库兼容说明。
- `TECHNICAL_DESIGN.md`：后端技术设计、数据流、数据库原则和当前阶段开发顺序。

## 阅读顺序

1. 先读 `CALL_CHAIN.md`，明确当前阶段必须遵守的链路。
2. 再读 `BACKEND_ARCHITECTURE.md`，理解每个包的职责。
3. 开发接口前读 `API_GUIDELINES.md`。
4. 连接本地 PostgreSQL 前读 `DATABASE_CONNECTION.md`。
5. 修改数据库、数据流或技术方案前读 `TECHNICAL_DESIGN.md`。

## 维护规则

- 后端接口变化：同步更新 `API_GUIDELINES.md`。
- 后端包结构变化：同步更新 `BACKEND_ARCHITECTURE.md`。
- 调用链变化：同步更新 `CALL_CHAIN.md`。
- 数据库或技术方案变化：同步更新 `TECHNICAL_DESIGN.md`。
- 前后端共同协议变化：同步更新公共文档 `../../docs/CFRP-1.0-前后端通信协议.md`。
