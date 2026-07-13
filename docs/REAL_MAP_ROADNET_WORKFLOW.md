# 真实地图路网生成流程

这份流程用于把人工校准后的真实地图区域，生成和 Jinan 场景结构一致的
CityFlow `roadnet.json` 与 `flow.json`，并支持当前后端策略调度。

## 1. 准备源路网配置

先复制模板：

```powershell
Copy-Item sim-python\data\source_network.example.json sim-python\data\custom_real_source.json
```

然后编辑 `custom_real_source.json`。

现在也可以在前端地图中采集：打开路网地图后，使用标题栏里的“真实路口”“边界点”“道路连接”按钮，在地图上点击生成源配置，最后点击“导出配置”下载 `custom_real_source.json`。

必须填写两类信息：

- `intersections`：真实信号路口和边界虚拟入口/出口点。
- `edges`：明确的道路连接关系，不要依赖横纵网格自动推断。

建议规则：

- 区域边界入口、出口点设置 `virtual: true`。
- 真实受控路口设置 `signalized: true`。
- 只有真实道路双向可行时才设置 `bidirectional: true`。
- 第一版尽量保持 `laneCount: 3`，因为当前后端相位与车道动作映射主要按 Jinan 的三车道约定设计。

## 2. 先校准路口坐标

高德地理编码只能作为初值。生成前要在地图上检查每个点：

- 是否落在真实十字路口中心；
- 是否偏到道路边缘、POI 或道路中段；
- 是否有重复路口；
- 是否遗漏边界入口/出口点。

如果坐标不准，先修改源配置，或通过前端校准工具拖拽后再导出。路口点不准时，后面的道路、laneLinks、flow 都会跟着偏。

## 3. 生成 CityFlow 文件

离线模式：缺少 `path` 的 edge 会使用直线段，并在校验报告中给出 warning。

```powershell
python scripts\build_cityflow_from_real_map.py `
  --input sim-python\data\custom_real_source.json `
  --out-dir sim-python\data\custom_real `
  --scene-id custom_real
```

高德路径模式：对缺少 `path` 的 edge 调用高德驾车路径规划 API。

```powershell
$env:AMAP_WEB_KEY="your_amap_web_service_key"
python scripts\build_cityflow_from_real_map.py `
  --input sim-python\data\custom_real_source.json `
  --out-dir sim-python\data\custom_real `
  --scene-id custom_real `
  --fetch-amap
```

输出文件：

- `sim-python/data/custom_real/roadnet.json`
- `sim-python/data/custom_real/flow.json`
- `sim-python/data/custom_real/validation_report.json`

使用场景前一定要先看 `validation_report.json`。

## 4. 注册场景

在 `sim-python/data/scenes.json` 中新增：

```json
{
  "sceneId": "custom_real",
  "name": "Custom real map roadnet",
  "sceneDir": "custom_real",
  "roadnetFile": "roadnet.json",
  "flowFile": "flow.json",
  "cityflowConfigMode": "generated"
}
```

## 5. 验证运行效果

先启动 `sim-python`，检查 Python 侧是否能读取新场景：

```powershell
Invoke-RestMethod http://127.0.0.1:9000/health
Invoke-RestMethod http://127.0.0.1:9000/cityflow/scenes/custom_real/roadnet
```

再启动 Spring Boot，检查后端代理：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/scenes/custom_real/roadnet
```

最后把前端 `simulationSceneId` 改为 `custom_real`，进行可视化和真实仿真测试。

## 生成器会自动补齐什么

生成器会创建：

- CityFlow 有向 roads；
- 默认三车道 lanes；
- 真实路口和虚拟边界路口；
- 每个真实路口的 incoming-to-outgoing `roadLinks`，默认排除掉头；
- 每个 `roadLink` 的 `laneLinks`；
- 9 个 light phases，其中 phase 2-5 对齐后端业务相位：`ETWT`、`NTST`、`ELWL`、`NLSL`；
- 从虚拟入口到虚拟出口的多路段 `flow.json` route；
- `validation_report.json`，用于检查缺失路径、非法引用、route 不连续、route 无 roadLink 等问题。

## 已知边界

- 生成器不会自动判断真实世界中哪些路口应该连通，连接关系必须来自 `edges`。
- 可以调用高德获取道路形状，但几何结果仍然需要人工复核。
- laneLinks 是可运行的工程近似，不是测绘级车道模型。
- 大型异形路口可能需要在生成后手工微调 `edges`、车道数或相位配置。
