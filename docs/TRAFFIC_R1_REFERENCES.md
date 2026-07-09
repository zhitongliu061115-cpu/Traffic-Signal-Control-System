# Traffic-R1 参考文档位置整理

## 定位

这 4 份 Traffic-R1 文档属于独立算法实验资料，用于说明 Traffic-R1 模型部署、LLMTSCS 复现、AutoDL 全量评测、MaxPressure baseline 对比和 wait-aware 改进方案。它们不是当前 Web 主系统的运行依赖，暂不直接混入 `backend`、`sim-python` 或 `sys-frontend`。

本次整理只在主项目中建立索引和关系说明，没有复制原文内容，避免把本机绝对路径、云端连接细节、模型文件路径和运行日志混入主系统文档。

## 文档清单

| 文档名 | 类型 | 主要内容 | 与主项目关系 |
|---|---|---|---|
| `Traffic-R1_相对MaxPressure改进说明.md` | 算法改进说明 | wait-aware score、prompt 增强、输出解析增强、guard 机制、与 MaxPressure 的指标对比目标 | 指导后续 `strategy` 模块中 Traffic-R1 / MaxPressure 策略设计 |
| `Traffic-R1_LLMTSCS_复现操作文档.md` | 本地复现文档 | WSL、GPU、Traffic-R1 8-bit 推理、LLMTSCS、baseline、30/60/120 周期评测流程 | 作为本地算法实验和指标验证入口 |
| `Traffic-R1_模型已下载后运行指南.md` | 日常运行指南 | 模型已下载后的快速进入、运行 Traffic-R1、运行 baseline、汇总日志和查看 guard 记录 | 作为团队重复评测和调参的快捷手册 |
| `Traffic-R1_AutoDL从零运行全量与对比模型指南.md` | 云端全量评测指南 | AutoDL 环境搭建、项目上传、依赖安装、10 周期冒烟、120 周期全量和 baseline 对比 | 作为云端长周期评测和正式指标采集流程 |

## 建议归档方式

当前建议保持“两层文档”：

1. 主系统仓库 `docs/PROJECT_BRIEF.md` 只写项目定位、模块边界、计划路线和算法资料摘要。
2. 主系统仓库 `docs/TRAFFIC_R1_REFERENCES.md` 只保留 Traffic-R1 文档索引、用途和接入关系。
3. 完整 Traffic-R1 运行命令、模型路径、云端环境和日志分析继续保留在独立 Traffic-R1 工作目录中。

如果后续需要把 Traffic-R1 资料正式纳入主系统仓库，建议先做一次脱敏整理，再放入：

```text
docs/references/traffic-r1/
```

并至少处理以下内容：

- 去掉本机绝对路径、云端连接地址、端口和任何可能关联个人环境的信息。
- 将模型目录、日志目录和服务器目录改成可配置占位符。
- 只保留可复现流程、指标解释、算法结论和主系统接入方式。

## 后续接入主系统的关系

Traffic-R1 实验稳定后，建议按以下路径接入主项目：

```text
Traffic-R1 / LLMTSCS 离线评测
  -> 输出稳定策略和指标结论
  -> Python 仿真算法服务封装策略调用
  -> Spring Boot strategy 模块统一生成 ControlDecision
  -> WebSocket 推送 rl.decision / sim.frame
  -> 前端展示 AI 控制前后指标对比
```

关键评估指标：

- 平均排队长度
- 累计排队车辆数
- 平均等待时间
- 平均旅行时间
- 输出解析失败数
- 与 FixedTime、Random、MaxPressure 的同周期对比结果

