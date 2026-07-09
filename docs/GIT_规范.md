# Git 使用规范

> 适用项目：**AI 自适应信号控制与应急绿波数字孪生系统**  
> 团队规模：5 人 · 轻量级流程 · 目标是「能协同、能回溯、不打架」。

---

## 0. 核心规则

- 开发前先同步远端最新代码。
- 不要直接在 `main` 或 `dev` 上开发。
- 每个功能、bug 修复、实验任务都新建一个分支。
- 日常开发从 `dev` 新建分支，开发完成后 Pull Request 回 `dev`。
- `main` 只放稳定、可演示、可交付版本。
- 尽量通过 PR 合并，不直接 push `main` / `dev`。
- 一个分支只做一件事，一个提交只表达一类变更。
- 改接口要同步 `backend/docs/API_GUIDELINES.md`。
- `.env`、密钥、令牌、模型权重、大文件不要提交。

---

## 1. 操作省流版

### 开发前固定流程

命令行：

```bash
git switch dev
git fetch origin
git pull origin dev
git switch -c feature/signal-adaptive-phase
```

旧版 Git 也可以用：

```bash
git checkout dev
git fetch origin
git pull origin dev
git checkout -b feature/signal-adaptive-phase
```

Visual Studio：

1. 切到 `dev` 分支。
2. 执行 Pull。
3. Git > New Branch。
4. 输入 `feature/scope-desc`、`fix/scope-desc` 等分支名。
5. 基于 `dev` 创建，并切换到新分支。
6. 开始开发。

### 开发后固定流程

命令行：

```bash
git status
git diff
git add .
git commit -m "feat(signal): 增加自适应相位决策"
git push -u origin feature/signal-adaptive-phase
```

然后去 GitHub 发 PR：

- base：`dev`
- compare：你的任务分支，例如 `feature/signal-adaptive-phase`

Visual Studio：

1. Git Changes 查看变更。
2. 填写提交信息并 Commit。
3. Push 到远端分支。
4. 去 GitHub 创建 PR。
5. PR 合并后切回 `dev`。
6. Pull 最新 `dev`。

### PR 合并后

命令行：

```bash
git switch dev
git pull origin dev
git branch -d feature/signal-adaptive-phase
```

如果远端分支没有自动删除，由 PR 合并者在 GitHub 页面删除。

---

## 2. 分支模型

本项目采用 **`main` + `dev` + 短生命周期任务分支**。

```text
main        <- 稳定演示/交付分支，只接受 dev 或 hotfix 合并
 └── dev    <- 日常集成分支，功能和修复先合到这里
      ├── feature/signal-adaptive-phase
      ├── feature/dashboard-network-render
      ├── fix/emergency-route-flicker
      └── exp/signal-dqn-vs-webster
```

| 分支 | 用途 | 规则 |
|---|---|---|
| `main` | 稳定、可演示版本 | 禁止直接 push；发布时由 `dev` 合并进入 |
| `dev` | 日常集成分支 | 禁止直接 push；任务分支通过 PR 合入 |
| `feature/*` | 新功能 | 从 `dev` 切出，完成后 PR 回 `dev` |
| `fix/*` | 缺陷修复 | 从 `dev` 切出，完成后 PR 回 `dev` |
| `hotfix/*` | 演示/答辩前紧急修复 | 从 `main` 切出，修完合回 `main` 和 `dev` |
| `exp/*` | 试验性工作 | 可不合并，允许删除 |

---

## 3. 分支命名

格式：

```text
<类型>/<scope>-<简短描述>
```

类型：

- `feature`：新功能。
- `fix`：普通缺陷修复。
- `hotfix`：紧急修复。
- `exp`：实验性工作。

scope：

| scope | 含义 |
|---|---|
| `core` | 公共基础、配置、协议、工具 |
| `sim` | 交通仿真、数据源 |
| `signal` | AI 自适应信号控制 |
| `emergency` | 应急绿波 |
| `agent` | 智能体、RAG 问答 |
| `dashboard` | 前端可视化大屏 |
| `docs` | 文档、规范、提示词 |

推荐示例：

```text
feature/signal-adaptive-phase
feature/emergency-green-wave
feature/dashboard-congestion-heat
feature/agent-rag-qa
fix/dashboard-vehicle-jitter
fix/core-ws-reconnect
hotfix/core-demo-crash
exp/signal-dqn-vs-webster
```

不推荐：

```text
new
test
fixbug
feature/我的功能
feature/update
```

---

## 4. 第一次接手项目

### 方式 A：命令行

1. 克隆仓库。

```bash
git clone <仓库地址>
cd Traffic-Signal-Control-System
```

2. 查看远端。

```bash
git remote -v
```

3. 拉取最新分支信息。

```bash
git fetch --all --prune
```

4. 切到 `dev` 并同步。

```bash
git switch dev
git pull origin dev
```

如果本地还没有 `dev`：

```bash
git switch -c dev origin/dev
```

### 方式 B：Visual Studio

1. 打开 Visual Studio。
2. 选择 Clone a repository。
3. 粘贴 GitHub 仓库地址。
4. 选择本地目录。
5. Clone 完成后打开项目。
6. 切到 `dev` 分支。
7. Pull 最新代码。

---

## 5. 每次开发前应该做什么

### 5.1 确认当前分支

命令行：

```bash
git branch
git status
```

Visual Studio：

- 看右下角或 Git 窗口里的当前分支名称。
- 打开 Git Repository 查看当前检出分支。
- 打开 Git Changes 查看是否有未提交文件。

### 5.2 切回 `dev` 并更新最新代码

命令行：

```bash
git switch dev
git fetch origin
git pull origin dev
```

Visual Studio：

1. 切到 `dev`。
2. Git > Pull。

说明：

- `fetch`：先获取远端变化，不直接改本地代码。
- `pull`：把远端更新合并到本地当前分支。

### 5.3 从最新 `dev` 新建任务分支

命令行：

```bash
git switch -c feature/dashboard-network-render
```

首次推送远端：

```bash
git push -u origin feature/dashboard-network-render
```

Visual Studio：

1. Git > New Branch。
2. 输入分支名。
3. 基于 `dev` 创建。
4. 勾选切换到新分支。
5. 创建完成后开始开发。

### 5.4 开发前确认工作区干净

命令行：

```bash
git status
```

如果不是 clean，先处理旧改动。不要把上次未完成内容带进新任务。

Visual Studio：

- 打开 Git Changes。
- 确认没有无关未提交文件。

---

## 6. 每次开发完成后应该做什么

### 6.1 检查改动

命令行：

```bash
git status
git diff
```

查看已暂存内容：

```bash
git diff --cached
```

Visual Studio：

- 在 Git Changes 看变更文件。
- 点开文件查看 diff。

检查重点：

- 是否只包含本次任务相关文件。
- 是否误提交 `.env`、密钥、日志、构建产物、大文件。
- 行为变化是否补测试或说明验证方式。
- 接口变更是否同步 `backend/docs/API_GUIDELINES.md`。

### 6.2 提交本次改动

命令行：

```bash
git add .
git commit -m "feat(signal): 增加基于排队长度的相位时长决策"
```

Visual Studio：

1. 打开 Git Changes。
2. 填写 message。
3. Commit。

### 6.3 推送到 GitHub 远端分支

命令行：

第一次推送：

```bash
git push -u origin feature/signal-adaptive-phase
```

后续推送：

```bash
git push
```

Visual Studio：

- Git Changes 窗口点击 Push。
- 或 Git > Push。

### 6.4 发起 Pull Request

GitHub 网页步骤：

1. 打开 GitHub 仓库。
2. 点击 Compare & pull request。
3. 选择 base：`dev`。
4. 选择 compare：你的任务分支。
5. 填写标题和描述。
6. 创建 PR。

PR 标题建议沿用提交格式：

```text
feat(signal): 增加基于排队长度的相位时长决策
```

---

## 7. 提交信息规范

采用简化版 Conventional Commits。

```text
<type>(<scope>): <一句话说清做了什么>
```

type：

| type | 场景 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 重构，不改变外部行为 |
| `perf` | 性能优化 |
| `docs` | 文档 |
| `style` | 格式、样式，不改变逻辑 |
| `test` | 测试 |
| `chore` | 依赖、脚本、工程杂项 |

要求：

- `scope` 必填，使用第 3 节的模块名。
- 描述用中文祈使句，不加句号。
- 一次提交只表达一类变更。
- 建议不超过 50 字。

示例：

```text
feat(signal): 增加基于排队长度的相位时长决策
fix(dashboard): 修复应急车辆路径高亮闪烁
perf(sim): 车辆位置更新改为增量推送
docs(agent): 补充 RAG 知识库入库说明
refactor(core): 统一 WebSocket 消息信封格式
```

反例：

```text
更新代码
fix: bug
feat(signal): 增加相位时长决策并顺便改大屏样式和仿真参数
```

---

## 8. Pull Request 规范

PR 目标分支：

- 日常功能和修复：`feature/*` / `fix/*` -> `dev`。
- 发布：`dev` -> `main`。
- 紧急修复：`hotfix/*` -> `main`，之后同步回 `dev`。

PR 合并要求：

- 至少 1 名其他成员 Review 通过。
- 核心模块 `signal`、`emergency`、`core` 建议 2 人 Review。
- 默认使用 Squash and merge。
- 合并后删除远端任务分支。

PR 描述模板：

```markdown
## 做了什么
- 

## 涉及模块
signal / dashboard / ...

## 如何验证
- 

## 风险与影响
- 是否有接口变更：
- 是否需要前后端同步：
- 是否包含 AI 生成代码：
```

---

## 9. 开发中同步远端更新

如果你的分支开发超过 1 天，或 `dev` 有重要更新，建议同步一次。

命令行：

```bash
git fetch origin
git switch feature/signal-adaptive-phase
git merge origin/dev
```

如果分支只在自己本地使用，也可以 rebase：

```bash
git fetch origin
git rebase origin/dev
```

规则：

- 多人共用或已经推送给别人基于开发的分支，不要随便 rebase。
- 冲突解决后重新跑相关验证。
- 不确定时用 `merge`，不要强推。

---

## 10. 发布与打标签

发布走 `dev -> main`。

流程：

1. `dev` 集成稳定后，由负责人发起 PR 到 `main`。
2. 合并前确认演示主流程可跑通。
3. 合并到 `main` 后打 tag。

Tag 格式：

```text
v<主版本>.<次版本>.<修订版本>
```

示例：

```text
v0.1.0
v0.2.0
v1.0.0
```

里程碑约定：

- `v0.1`：数据大屏跑通。
- `v0.2`：自适应信号控制跑通。
- `v0.3`：应急绿波跑通。
- `v0.4`：智能体问答跑通。
- `v1.0`：答辩版本。

---

## 11. Hotfix 流程

仅在 `main` 已发布或即将演示时使用。

命令行：

```bash
git switch main
git pull origin main
git switch -c hotfix/core-demo-crash
```

修复后：

```bash
git add .
git commit -m "fix(core): 修复演示环境启动失败"
git push -u origin hotfix/core-demo-crash
```

然后：

1. PR 到 `main`。
2. 合并后打修订版本 tag。
3. 再把 `main` 的修复同步回 `dev`。
4. 在 PR 说明中写清触发场景、修复方式和验证结果。

---

## 12. 不入库清单

`.gitignore` 必须覆盖以下内容。

```gitignore
# 依赖与构建
node_modules/
dist/
__pycache__/
*.pyc
.venv/

# 密钥与配置
.env
.env.*
*.key

# 模型与大文件
*.pt
*.pth
*.ckpt
vector_store/
sumo_output/

# 编辑器 / 系统
.vscode/
.idea/
.DS_Store
```

模型权重、向量库、SUMO 仿真输出等大文件不进普通 Git。需要共享时，使用网盘、对象存储或 Git LFS，并在 README 写清获取方式。

---

## 13. 名词解释

- Repository / 仓库：项目代码存放的地方。
- Clone：把远程仓库下载到本地。
- Commit：把本地修改保存成一个版本记录。
- Push：把本地提交上传到 GitHub。
- Fetch：获取远端最新变化，但不合并到当前分支。
- Pull：把远端最新代码拉到本地并合并。
- Branch / 分支：每个人或每个任务独立开发的线路。
- Merge：把一个分支的改动合并到另一个分支。
- Pull Request / PR：请求把自己的分支合并到目标分支。
- `main`：稳定演示/交付分支。
- `dev`：日常开发集成分支。
- `feature/xxx`：功能分支。
- `fix/xxx`：缺陷修复分支。
- `hotfix/xxx`：紧急修复分支。
- `exp/xxx`：实验分支。

---

## 14. 协作红线

1. 不直接 push `main` / `dev`，一律走 PR。
2. 不提交 `.env`、密钥、令牌、真实账号、私有数据。
3. 不把多个无关任务塞进一个分支或一个 PR。
4. 不在未说明风险的情况下改接口、改数据结构、改部署方式。
5. 不用 `git reset --hard`、强推、批量删除等高风险操作处理共享分支。
6. 发现误提交敏感信息，立即停止扩散，通知负责人并轮换密钥。
