# 安全守护模块设计说明

## 1. 背景与目标

信号配时是交通控制的核心环节，错误的相位切换（如东西直行 ↔ 南北直行同时放行）会直接导致路口死锁。本模块在 `sim-python` 的 `set_tl_phase` 调用前设置一层统一安全守卫，拦截所有危险配时变更。

### 设计目标

| 目标 | 说明 |
|---|---|
| 防冲突 | 同一路口或相邻路口的冲突相位不能同时放行 |
| 防瞬切 | 相位切换必须有最小绿灯间隔，给驾驶员反应时间 |
| 应急豁免 | 应急车辆优先通行时跳过最小绿限制，但对冲突检测仍然生效 |
| 非侵入 | 不修改现有算法逻辑，只拦截 `set_tl_phase()` 调用 |
| 全链路覆盖 | 同时拦截 Java 算法（HTTP）和 Python 应急（直接调用）两条控制流 |

## 2. 架构位置

安全守护作为 Python 层的拦截器，嵌入在 `engine.py` 的 `_safe_set_tl_phase()` 方法中，覆盖所有 `set_tl_phase()` 的调用入口：

```
Java 算法 (FixedTime / MaxPressure / Traffic-R)
  → HTTP apply_control_actions
    → engine._safe_set_tl_phase()
      → safety_guard.guard()          ← 安全检查

Python 应急绿波 (EVPriorityService)
  → engine.next_frame → ev_service.step()
    → engine._safe_set_tl_phase(is_emergency=True)
      → safety_guard.guard()          ← 安全检查（跳过最小绿）

Python 固定周期轮转 (AUTO_SIGNAL_CYCLE=true)
  → engine._advance_signal_phases()
    → engine._safe_set_tl_phase()
      → safety_guard.guard()          ← 安全检查
```

## 3. 三层安全防护

```
guard() 检查流程：
  输入: current_phase, target_phase, sim_time, is_emergency

  ├── 同相位检查 → 记录时间，直接通过
  │
  ├── Layer 1: 参数门卫（预留）
  │   └── validate_green_duration()   ← 当前未在 guard() 中启用
  │
  ├── Layer 3: 最小绿灯保护
  │   └── check_min_green()           ← 应急可跳过
  │       距上次切相不满 10s → 拒绝
  │
  ├── Layer 2: 同路口冲突检测
  │   └── check_phase_conflict()
  │       ETWT ↔ NTST  互斥
  │       ELWL ↔ NLSL  互斥
  │
  ├── Layer 2: 相邻路口冲突检测
  │   └── check_adjacent_conflict()
  │       相邻路口也不能同时放行冲突相位
  │
  └── 全部通过 → 记录时间，放行
```

### 3.1 Layer 1 — 参数门卫（预留）

校验绿灯时长是否在安全范围内。当前已定义 `validate_green_duration()` 方法，但 `guard()` 中未启用此检查，因为各算法对时长的需求不同，FixedTime 使用固定周期，自适应算法自动调节。后续如需统一上限可在 `guard()` 中按需加入。

### 3.2 Layer 2 — 相位冲突检测

**同路口冲突矩阵**

```
CONFLICTING_PAIRS:
  (2, 3), (3, 2)   ← ETWT(东西直行) ↔ NTST(南北直行)
  (4, 5), (5, 4)   ← ELWL(东西左转) ↔ NLSL(南北左转)
```

相位 Index 说明（业务相位，CityFlow 中为 phaseIndex - 1）：

| Code | Index | 方向 |
|---|---|---|
| ETWT | 2 | 东西直行 |
| NTST | 3 | 南北直行 |
| ELWL | 4 | 东西左转 |
| NLSL | 5 | 南北左转 |

**相邻路口冲突**
通过路网拓扑查找当前路口的相邻路口，检查相邻路口的当前相位是否与目标相位冲突。相邻冲突检测需要传入 `roadnet_roads` 参数，仅在 `engine.py` 的 `_safe_set_tl_phase()` 中传入。

### 3.3 Layer 3 — 过渡安全（最小绿保护）

防止相位频繁切换（瞬切）。每次成功切相时记录当前 `sim_time`，后续切换请求必须满足距上次切相 ≥ 10s 才放行。

- 正常切换：必须满足最小绿 10s
- 应急车辆：`is_emergency=True` 跳过此检查

## 4. 接口与返回值

### guard() 接口

```python
def guard(
    self,
    sid: str,                           # 会话 ID（用于隔离不同会话）
    inter_id: str,                      # 路口 ID
    current_phase: int,                 # 当前相位 Index
    target_phase: int,                  # 目标相位 Index
    sim_time: float,                    # 当前仿真时间
    current_phases: dict[str, int],     # 所有路口当前相位（相邻检测用）
    roadnet_roads: list[dict] | None,   # 路网道路列表（相邻检测用）
    is_emergency: bool = False,         # 是否应急车辆
) -> tuple[bool, str, int]
```

返回值：

| 字段 | 类型 | 说明 |
|---|---|---|
| passed | bool | True 表示安全检查通过 |
| message | str | 检查结果说明 |
| safe_phase | int | 通过的相位或建议保持的相位 |

## 5. 集成方式

### engine.py 改动

替换引擎中所有直接的 `session.engine.set_tl_phase()` 调用为 `self._safe_set_tl_phase()` 包装方法，共 3 处：

| 调用位置 | 来源 | is_emergency |
|---|---|---|
| `apply_control_actions()` | Java 算法（HTTP） | False |
| `next_frame()` EV 覆盖 | Python 应急服务 | True |
| `_advance_signal_phases()` | Python 内部固定轮转 | False |

### _safe_set_tl_phase() 实现

```python
def _safe_set_tl_phase(self, session, intersection_id, cityflow_phase_id,
                        sim_time, is_emergency=False):
    phase_index = cityflow_phase_id + 1
    current = session.current_phases.get(intersection_id)

    if current is None:
        session.engine.set_tl_phase(...)
        session.current_phases[intersection_id] = phase_index
        return True

    ok, msg, safe_phase = self.safety_guard.guard(...)

    if ok:
        session.engine.set_tl_phase(...)
        session.current_phases[intersection_id] = safe_phase
        return True
    else:
        log.warning(...)
        return False
```

### 响应处理

`apply_control_actions()` 中只有 `_safe_set_tl_phase()` 返回 True 的路口才会出现在 `applied` 列表中，使得 Java 端感知到哪些决策被采纳：

```python
ok = self._safe_set_tl_phase(...)
if ok:
    applied.append({...})   # 只有成功才告知 Java
```

## 6. 安全边界

| 场景 | 行为 | 是否预期 |
|---|---|---|
| 同相位请求 | 记录时间，直接通过 | ✅ |
| 非法相位切换（ETWT ↔ NTST） | 拒绝，保持当前相位 | ✅ |
| 10s 内连续请求 | 拒绝（非应急） | ✅ |
| 应急车辆 10s 内切相 | 通过 | ✅ |
| 应急车辆非法切换 | 拒绝（不放过冲突） | ✅ |
| Java 算法拒绝后响应 | `applied` 列表不包含该路口 | ✅ |
| 相邻路口冲突 | 拒绝 | ✅ |

## 7. 配置文件

安全阈值定义在 `safety_guard.py`，可通过环境变量覆盖（设计预留）：

| 常量 | 默认值 | 说明 |
|---|---|---|
| `MIN_GREEN_SEC` | 10 | 最小绿灯时间（秒） |
| `MAX_GREEN_SEC` | 60 | 最大绿灯时间（秒，仅 validate_green_duration 定义，当前 guard() 未使用） |
| `MIN_CYCLE_SEC` | 40 | 最小周期长度（秒，预留） |
| `MAX_CYCLE_SEC` | 180 | 最大周期长度（秒，预留） |

## 8. 相关文件

| 文件 | 角色 |
|---|---|
| `sim-python/app/safety_guard.py` | 安全守护核心模块 |
| `sim-python/app/engine.py` | 集成 _safe_set_tl_phase() 包装方法 |
| `sim-python/app/ev_service.py` | 集成 safety_guard 日志 |
