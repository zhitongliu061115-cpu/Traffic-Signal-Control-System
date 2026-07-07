# 团队 Prompt 库

记录可复用的 AI 辅助开发提示词。新增 Prompt 时请说明适用模块、输入上下文和期望输出。

## 后端接口实现

```text
请在 backend 的 FastAPI 项目中实现 <接口名称>。
必须遵守 docs/API_GUIDELINES.md：
- 路径使用 /api/v1
- 响应封装为 { code, msg, data }
- 字段使用 camelCase
- 不硬编码密钥、IP、真实数据源
请同时补充最小测试。
```

## 前端大屏组件

```text
请在 sys-frontend 中实现 <组件名称>。
上下文：
- 前端使用 Vue + TypeScript + Pinia
- API 字段以 docs/API_GUIDELINES.md 为准
- 大屏需要展示路口、道路、车辆、信号灯和交通状态
要求：
- 不新增无必要第三方依赖
- 组件状态清晰，样式与现有项目一致
- 给出如何验证的步骤
```

## 信号控制策略

```text
请实现 signal 模块的 <策略名称>。
输入包括路口、相位、排队长度、平均速度、拥堵度。
输出下一相位 phaseId 和绿灯时长 greenSec。
约束：
- 相位编号从 1 开始
- 时间单位为秒
- 绿灯时长必须有上下限
- 解释每个决策指标的作用
- 补充边界测试
```
