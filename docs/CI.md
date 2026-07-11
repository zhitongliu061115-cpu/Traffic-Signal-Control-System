# CI 说明

本项目当前配置了基础 CI，用于在代码进入主分支前做最小质量检查。

当前检查范围：

- `sys-frontend`：安装依赖、类型检查、单元测试、构建。
- `backend`：执行 Maven 测试。
- `sim-python`：执行 Python 单元测试。

CI 配置文件位于 `.github/workflows/ci.yml`。
