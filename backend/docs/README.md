# 后端文档目录

本目录只保存 Spring Boot 主后端和 Python 仿真服务相关文档，避免和项目公共文档混淆。

## 文档列表

- `API_GUIDELINES.md`：REST / WebSocket / Python 服务接口协作规范。
- `BACKEND_ARCHITECTURE.md`：后端包结构、依赖方向、模块职责和扩展规则。
- `CALL_CHAIN.md`：当前阶段固定调用链，明确前端、Spring Boot、Python CityFlow 服务和 WebSocket 的边界。
- `TECHNICAL_DESIGN.md`：后端技术设计、数据流、数据库原则和当前阶段开发顺序。
- `DEPLOYMENT.md`：本地联调、云端部署、服务地址配置和部署风险说明。
- `RISK_TODO.md`：后端风险、半成品、漏洞和 TODO 清单。

## 阅读顺序

1. 先读 `CALL_CHAIN.md`，明确当前阶段必须遵守的链路。
2. 再读 `BACKEND_ARCHITECTURE.md`，理解每个包的职责。
3. 开发接口前读 `API_GUIDELINES.md`。
4. 修改数据库、数据流或技术方案前读 `TECHNICAL_DESIGN.md`。
5. 部署或联调环境变化前读 `DEPLOYMENT.md`。
6. 开始新任务或接手他人代码前读 `RISK_TODO.md`，确认已有风险和未完成事项。

## 维护规则

- 后端接口变化：同步更新 `API_GUIDELINES.md`。
- 后端包结构变化：同步更新 `BACKEND_ARCHITECTURE.md`。
- 调用链变化：同步更新 `CALL_CHAIN.md`。
- 数据库或技术方案变化：同步更新 `TECHNICAL_DESIGN.md`。
- 部署方式、服务地址或运行环境变化：同步更新 `DEPLOYMENT.md`。
- 发现半成品、不完善实现、潜在漏洞或可能误导团队的代码：必须同步更新 `RISK_TODO.md`。
- 前后端共同协议变化：同步更新公共文档 `../../docs/CFRP-1.0-前后端通信协议.md`。
