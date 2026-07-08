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

- 页面加载后只创建会话并连接 WebSocket，不会自动推进仿真。
- 点击“启动”调用 `POST /api/v1/simulations/{sid}/start`。
- 点击“暂停”调用 `POST /api/v1/simulations/{sid}/pause`，Spring Boot 停止轮询 Python frame 接口，CityFlow 停在当前帧。
- 点击“停止”调用 `POST /api/v1/simulations/{sid}/stop`，当前会话结束，需刷新页面创建新会话。
- 为提高车辆动画流畅度，Spring Boot 默认每 200ms 获取一帧；真实 CityFlow 的 `interval` 同步设置为 0.2 秒，所以信号灯相位仍按仿真时间正常切换，不会因为帧率提高而快进。

## 说明

- 页面只做可视化联调，不包含智能体、策略控制和复杂控制面板。
- WebSocket 地址按 CFRP 协议使用 `/ws/v1/simulations/{sid}`。
- 前端只连接 Spring Boot，不直接连接 Python CityFlow 服务。
