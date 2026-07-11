# Three.js 临时仿真联调前端

该目录是临时测试前端，不使用 `sys-frontend`，只用于验证：

```text
Three.js 页面
  -> Spring Boot REST / WebSocket
  -> Python CityFlow 服务
  -> CityFlow Engine
```

## 启动

先启动 Python CityFlow 服务和 Spring Boot 后端，然后运行：

```powershell
cd temp-three-frontend
npm.cmd install
npm.cmd run dev
```

默认连接：

```text
http://127.0.0.1:8080
```

如果 Spring Boot 使用其他端口，可以在 URL 上指定：

```text
http://127.0.0.1:5174/?api=http://127.0.0.1:18080
```

## 当前实现顺序

1. 调用 `GET /api/v1/scenes/jinan_3x4/roadnet`
2. 绘制路口节点
3. 绘制道路折线
4. 根据 `laneCount` 调整道路宽度
5. 创建仿真会话
6. 连接 WebSocket
7. 接收 `sim.frame`
8. 根据 `vehicles[].id` 复用车辆对象
9. 用上一帧坐标到当前帧坐标做插值动画
10. 根据 `roads[].level` 改变道路颜色
11. 根据 `signals[].phaseIndex` 高亮信号灯和放行方向

## 视角操作

- 鼠标滚轮：缩放
- 左键拖拽：平移
- 右键拖拽：旋转观察
- `R`：重置到全局路网视角
- 空格：启动 / 暂停仿真

## 仿真控制

- 顶部“控制方法”可选择 `RL`、`Max Pressure`、`固定配时`。
- 顶部 `Scene` 可选择 `Jinan Stress` 或 `Jinan Normal`。`Jinan Stress` 使用同一路网和高流量 flow，默认用于快速观察拥堵与调度效果。
- 顶部 `Speed` 可选择仿真倍率，默认 `5x`，用于更快推进 CityFlow 时间并观察拥堵和策略效果。
- 控制方法只在创建仿真会话时生效；切换后会停止当前会话并创建新会话。
- 选择 `RL` 时，前端会创建 `controllerType=traffic-r` 的仿真会话，由 Spring Boot 调用云端 Traffic-R，再将统一 `ControlDecision` 下发给 CityFlow。
- 页面左侧 CityFlow 应用面板会监听 WebSocket `control.decision` 消息，用于展示后端已生成并提交的控制动作；真实信号灯颜色仍只来自后续 `sim.frame.data.signals`。
- 页面加载后只创建会话并连接 WebSocket，不会自动推进仿真。
- 点击“启动”调用 `POST /api/v1/simulations/{sid}/start`。
- 点击“暂停”调用 `POST /api/v1/simulations/{sid}/pause`，Spring Boot 停止轮询 Python frame 接口，CityFlow 停在当前帧。
- 点击“停止”调用 `POST /api/v1/simulations/{sid}/stop`，当前会话结束，需刷新页面创建新会话。
- 为提高车辆动画流畅度，Spring Boot 默认每 100ms 读取一次 CityFlow 缓存快照；真实 CityFlow 后台 worker 按 `SIM_REALTIME_TICK_SECONDS` 连续推进仿真。真实 CityFlow 默认不再由 Python 自动固定周期切换信号灯，相位应由 Spring Boot 下发的策略决策控制。

## 说明

- 页面只做仿真和策略调度联调，不包含智能体、权限管理和正式业务控制面板。
- WebSocket 地址按 CFRP 协议使用 `/ws/v1/simulations/{sid}`。
- 前端只连接 Spring Boot，不直接连接 Python CityFlow 服务。
