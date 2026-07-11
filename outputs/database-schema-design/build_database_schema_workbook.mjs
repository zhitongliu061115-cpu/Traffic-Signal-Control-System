import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const outputDir = path.resolve("outputs/database-schema-design");
const outputPath = path.join(outputDir, "Traffic-Signal-Database-Schema-Design.xlsx");

const columns = [
  "业务域",
  "完成优先级",
  "表名",
  "表使用场景",
  "字段名",
  "类型建议",
  "字段具体介绍",
  "字段使用场景",
  "范式/设计说明",
];

const domains = [
  {
    sheet: "00_总览",
    description: "按业务域汇总所有建议表，便于项目排期、建表拆分和接口落库对齐。",
    rows: [],
  },
  {
    sheet: "01_路网地图",
    description: "保存 CityFlow 静态路网、真实地图绑定、车道和转向拓扑，是仿真、地图点击和 lane-level 策略输入的基础。",
    rows: [
      ["路网与地图绑定", "P0", "scene", "管理不同仿真/真实地图场景，例如 jinan_3x4、压测场景、大路网场景。", "id", "uuid PK", "场景主键。", "所有路网、仿真会话、区域配置通过 scene_id 关联。", "主键决定整行；scene_code 唯一，满足 BCNF。"],
      ["路网与地图绑定", "P0", "scene", "管理不同仿真/真实地图场景，例如 jinan_3x4、压测场景、大路网场景。", "scene_code", "varchar unique", "业务场景编码，如 jinan_3x4、real_map_core。", "前端和后端创建仿真时选择场景。", "业务候选键，避免用名称作为外键。"],
      ["路网与地图绑定", "P0", "scene", "管理不同仿真/真实地图场景，例如 jinan_3x4、压测场景、大路网场景。", "name", "varchar", "场景显示名称。", "前端下拉、文档展示。", "依赖 scene 主键。"],
      ["路网与地图绑定", "P0", "scene", "管理不同仿真/真实地图场景，例如 jinan_3x4、压测场景、大路网场景。", "source_type", "varchar", "数据来源类型，如 cityflow_file、amap_import、manual。", "区分 Jinan 文件导入和真实地图导入。", "枚举值建议代码层约束。"],
      ["路网与地图绑定", "P0", "scene", "管理不同仿真/真实地图场景，例如 jinan_3x4、压测场景、大路网场景。", "cityflow_roadnet_path", "varchar", "CityFlow roadnet 文件路径。", "云端 CityFlow 或本地仿真启动时定位文件。", "路径属于场景属性，不拆表。"],
      ["路网与地图绑定", "P0", "scene", "管理不同仿真/真实地图场景，例如 jinan_3x4、压测场景、大路网场景。", "cityflow_flow_path", "varchar", "CityFlow flow 文件路径。", "创建仿真会话时加载车流。", "路径属于场景属性，不拆表。"],
      ["路网与地图绑定", "P1", "scene", "管理不同仿真/真实地图场景，例如 jinan_3x4、压测场景、大路网场景。", "map_provider", "varchar", "地图服务来源，如 amap、osm。", "真实地图底图绑定和坐标解释。", "依赖 scene。"],
      ["路网与地图绑定", "P1", "scene", "管理不同仿真/真实地图场景，例如 jinan_3x4、压测场景、大路网场景。", "coordinate_system", "varchar", "坐标系，如 gcj02、wgs84、cityflow_xy。", "地图坐标和 CityFlow 坐标转换。", "不要和每个路口重复存储。"],
      ["路网与地图绑定", "P0", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "id", "uuid PK", "路口主键。", "被 road、road_link、signal_phase、快照等表引用。", "主键决定整行。"],
      ["路网与地图绑定", "P0", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "scene_id", "uuid FK", "所属场景。", "同一个 CityFlow ID 可在不同场景复用。", "联合唯一建议 (scene_id, cityflow_id)。"],
      ["路网与地图绑定", "P0", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "cityflow_id", "varchar", "CityFlow roadnet 中的 intersection id。", "仿真帧 signals/intersections 关联静态路口。", "不直接作为全局 PK，避免跨场景冲突。"],
      ["路网与地图绑定", "P1", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "map_intersection_id", "varchar", "真实地图平台中的路口标识。", "地图点击路口后反查 CityFlow 路口。", "可为空；真实地图场景建议唯一。"],
      ["路网与地图绑定", "P1", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "name", "varchar", "路口名称。", "前端展示、Agent 回答。", "依赖路口主键。"],
      ["路网与地图绑定", "P1", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "type", "varchar", "路口类型，如 standard_cross、t_junction、complex。", "决定是否适合 Traffic-R 控制。", "分类可冗余缓存，复杂规则另建配置。"],
      ["路网与地图绑定", "P0", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "virtual", "boolean", "是否虚拟路口。", "过滤真实控制路口和边界路口。", "来自 CityFlow roadnet。"],
      ["路网与地图绑定", "P1", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "longitude", "decimal(10,7)", "真实地图经度。", "地图定位和选点吸附。", "与 x/y 不互相决定，允许并存。"],
      ["路网与地图绑定", "P1", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "latitude", "decimal(10,7)", "真实地图纬度。", "地图定位和选点吸附。", "与 x/y 不互相决定，允许并存。"],
      ["路网与地图绑定", "P0", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "x", "double precision", "CityFlow 平面坐标 x。", "前端仿真渲染和路径吸附。", "依赖路口。"],
      ["路网与地图绑定", "P0", "intersection", "保存 CityFlow 路口与地图真实路口绑定，支持地图点击进入路口详情。", "y", "double precision", "CityFlow 平面坐标 y。", "前端仿真渲染和路径吸附。", "依赖路口。"],
      ["路网与地图绑定", "P0", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "id", "uuid PK", "道路主键。", "车辆状态、道路状态和 lane 都通过 road_id 关联。", "主键决定整行。"],
      ["路网与地图绑定", "P0", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "scene_id", "uuid FK", "所属场景。", "按场景加载路网。", "联合唯一建议 (scene_id, cityflow_id)。"],
      ["路网与地图绑定", "P0", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "cityflow_id", "varchar", "CityFlow road id。", "仿真帧 vehicles[].roadId、roads[].id 对齐。", "跨场景不全局唯一。"],
      ["路网与地图绑定", "P0", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "from_intersection_id", "uuid FK", "道路起点路口。", "构建有向图、规划路径。", "依赖 road，不由 cityflow_id 传递决定。"],
      ["路网与地图绑定", "P0", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "to_intersection_id", "uuid FK", "道路终点路口。", "构建有向图、应急路线转 road_route。", "依赖 road。"],
      ["路网与地图绑定", "P1", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "name", "varchar", "道路名称。", "地图展示、Agent 回答。", "真实地图可为空。"],
      ["路网与地图绑定", "P1", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "direction", "varchar", "道路大方向或通行方向。", "前端箭头、导向说明。", "可从 geometry 推导但缓存便于查询。"],
      ["路网与地图绑定", "P0", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "length_m", "double precision", "道路长度。", "路径规划边权、旅行时间估计。", "可由 geometry 计算；为性能保留派生值时需同步。"],
      ["路网与地图绑定", "P1", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "speed_limit", "double precision", "限速或仿真最大速度。", "应急路径、预计行驶时间。", "道路属性。"],
      ["路网与地图绑定", "P0", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "lane_count", "integer", "车道数量。", "前端绘制道路宽度、校验 lane 数。", "可由 lane 表统计，属于冗余缓存；建议用于展示，真实以 lane 表为准。"],
      ["路网与地图绑定", "P0", "road", "保存 CityFlow 道路、真实地图道路折线和道路拓扑。", "geometry", "jsonb / geometry", "道路折线点序列。", "车辆沿曲线道路渲染。", "PostGIS 可用 geometry；否则 JSONB 存点列。"],
      ["路网与地图绑定", "P0", "lane", "保存道路下的车道结构，是 lane-level 输入和真实车辆渲染基础。", "id", "uuid PK", "车道主键。", "lane_state_snapshot、lane_link 关联。", "主键决定整行。"],
      ["路网与地图绑定", "P0", "lane", "保存道路下的车道结构，是 lane-level 输入和真实车辆渲染基础。", "road_id", "uuid FK", "所属道路。", "定位车道所属 road。", "联合唯一建议 (road_id, cityflow_lane_index)。"],
      ["路网与地图绑定", "P0", "lane", "保存道路下的车道结构，是 lane-level 输入和真实车辆渲染基础。", "cityflow_lane_index", "integer", "CityFlow lane index。", "映射 engine lane_id 的后缀。", "与 road_id 组成候选键。"],
      ["路网与地图绑定", "P0", "lane", "保存道路下的车道结构，是 lane-level 输入和真实车辆渲染基础。", "lane_code", "varchar", "业务车道编码。", "地图标注、检测器绑定。", "不作为唯一业务事实。"],
      ["路网与地图绑定", "P0", "lane", "保存道路下的车道结构，是 lane-level 输入和真实车辆渲染基础。", "direction", "varchar", "进口方向，如 east、west、north、south。", "生成 WT/WL 等 movement 状态。", "依赖 lane。"],
      ["路网与地图绑定", "P0", "lane", "保存道路下的车道结构，是 lane-level 输入和真实车辆渲染基础。", "movement", "varchar", "车道转向类型，如 straight、left、right。", "Traffic-R lane-level 输入、MaxPressure movement 计算。", "依赖 lane。"],
      ["路网与地图绑定", "P1", "lane", "保存道路下的车道结构，是 lane-level 输入和真实车辆渲染基础。", "width", "double precision", "车道宽度。", "前端车道级绘制。", "依赖 lane。"],
      ["路网与地图绑定", "P1", "lane", "保存道路下的车道结构，是 lane-level 输入和真实车辆渲染基础。", "speed_limit", "double precision", "车道限速。", "仿真/路径估计。", "若所有 lane 同 road，可为空继承 road。"],
      ["路网与地图绑定", "P0", "road_link", "保存路口内道路转向关系，连接进入道路和驶出道路。", "id", "uuid PK", "转向连接主键。", "相位放行、MaxPressure、路口详情转向箭头。", "主键决定整行。"],
      ["路网与地图绑定", "P0", "road_link", "保存路口内道路转向关系，连接进入道路和驶出道路。", "intersection_id", "uuid FK", "所属路口。", "查询某路口全部转向。", "联合唯一建议 (intersection_id, cityflow_index)。"],
      ["路网与地图绑定", "P0", "road_link", "保存路口内道路转向关系，连接进入道路和驶出道路。", "cityflow_index", "integer", "CityFlow roadLinks 数组索引。", "signal_phase_road_link 按索引映射。", "与 intersection_id 组成候选键。"],
      ["路网与地图绑定", "P0", "road_link", "保存路口内道路转向关系，连接进入道路和驶出道路。", "from_road_id", "uuid FK", "进入路口的道路。", "压力计算中的上游入口。", "依赖 road_link。"],
      ["路网与地图绑定", "P0", "road_link", "保存路口内道路转向关系，连接进入道路和驶出道路。", "to_road_id", "uuid FK", "驶出路口的道路。", "压力计算中的下游出口。", "依赖 road_link。"],
      ["路网与地图绑定", "P0", "road_link", "保存路口内道路转向关系，连接进入道路和驶出道路。", "movement_type", "varchar", "转向类型，如 go_straight、turn_left、turn_right。", "相位显示、应急优先相位匹配。", "依赖 road_link。"],
      ["路网与地图绑定", "P1", "lane_link", "保存车道级转向连接和路口内转弯轨迹。", "id", "uuid PK", "车道连接主键。", "车辆转弯动画、lane-level 精细分析。", "主键决定整行。"],
      ["路网与地图绑定", "P1", "lane_link", "保存车道级转向连接和路口内转弯轨迹。", "road_link_id", "uuid FK", "所属 road_link。", "从道路级转向进入车道级转向。", "联合唯一建议 (road_link_id, start_lane_id, end_lane_id)。"],
      ["路网与地图绑定", "P1", "lane_link", "保存车道级转向连接和路口内转弯轨迹。", "start_lane_id", "uuid FK", "起始车道。", "确定车辆从哪个 lane 进入路口。", "依赖 lane_link。"],
      ["路网与地图绑定", "P1", "lane_link", "保存车道级转向连接和路口内转弯轨迹。", "end_lane_id", "uuid FK", "目标车道。", "确定车辆驶出路口后所在 lane。", "依赖 lane_link。"],
      ["路网与地图绑定", "P1", "lane_link", "保存车道级转向连接和路口内转弯轨迹。", "geometry", "jsonb / geometry", "路口内转弯曲线点。", "路口详情页车辆真实转弯动画。", "几何数据随 lane_link 存储。"],
    ],
  },
  {
    sheet: "02_信号安全",
    description: "保存信号相位、配时方案、相位放行关系和安全约束，是所有控制策略下发前的硬校验基础。",
    rows: [
      ["信号相位与安全约束", "P0", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "id", "uuid PK", "相位主键。", "control_decision、transition_rule 关联。", "主键决定整行。"],
      ["信号相位与安全约束", "P0", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "intersection_id", "uuid FK", "所属路口。", "查询某路口候选相位。", "联合唯一建议 (intersection_id, phase_index)。"],
      ["信号相位与安全约束", "P0", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "phase_index", "integer", "CityFlow lightphase 序号或业务相位序号。", "下发 CityFlow set_tl_phase 前映射。", "与 intersection_id 组成候选键。"],
      ["信号相位与安全约束", "P0", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "phase_code", "varchar", "业务相位编码，如 ETWT、NTST、PHASE_6。", "Traffic-R/MaxPressure 输出统一识别。", "可与 intersection_id 设唯一。"],
      ["信号相位与安全约束", "P1", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "phase_name", "varchar", "相位中文名称。", "前端和 Agent 解释。", "依赖 phase。"],
      ["信号相位与安全约束", "P1", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "phase_type", "varchar", "相位类型，如 through、left、right、pedestrian、all_red。", "安全层判断特殊相位。", "依赖 phase。"],
      ["信号相位与安全约束", "P0", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "default_green_sec", "integer", "默认绿灯秒数。", "固定配时和 fallback 默认值。", "相位属性。"],
      ["信号相位与安全约束", "P0", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "yellow_sec", "integer", "黄灯秒数。", "相位切换过渡。", "可由安全约束覆盖。"],
      ["信号相位与安全约束", "P0", "signal_phase", "保存每个路口真实可用相位，不写死四相位。", "all_red_sec", "integer", "全红秒数。", "冲突相位切换保护。", "可由安全约束覆盖。"],
      ["信号相位与安全约束", "P0", "signal_phase_road_link", "多对多保存相位放行哪些 roadLink。", "phase_id", "uuid FK", "相位 ID。", "查询某相位放行方向。", "复合主键 (phase_id, road_link_id)，满足 BCNF。"],
      ["信号相位与安全约束", "P0", "signal_phase_road_link", "多对多保存相位放行哪些 roadLink。", "road_link_id", "uuid FK", "被放行的转向连接。", "前端高亮放行方向、MaxPressure 候选 movement。", "消除 signal_phase.movements JSONB 冗余。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan", "保存固定配时、绿波方案、人工方案、算法生成方案。", "id", "uuid PK", "配时方案主键。", "方案版本管理。", "主键决定整行。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan", "保存固定配时、绿波方案、人工方案、算法生成方案。", "intersection_id", "uuid FK", "所属路口。", "查询路口可用配时方案。", "联合唯一建议 (intersection_id, plan_code)。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan", "保存固定配时、绿波方案、人工方案、算法生成方案。", "plan_code", "varchar", "方案编码。", "版本识别和人工配置。", "与 intersection_id 组成候选键。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan", "保存固定配时、绿波方案、人工方案、算法生成方案。", "name", "varchar", "方案名称。", "前端展示。", "依赖 plan。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan", "保存固定配时、绿波方案、人工方案、算法生成方案。", "source", "varchar", "方案来源，如 manual、fixed-time、traffic-r、greenwave。", "区分人工方案和算法方案。", "依赖 plan。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan", "保存固定配时、绿波方案、人工方案、算法生成方案。", "cycle_sec", "integer", "信号周期。", "固定配时和协调控制。", "依赖 plan。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan", "保存固定配时、绿波方案、人工方案、算法生成方案。", "offset_sec", "integer", "协调相位差。", "绿波/干线协调。", "依赖 plan。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan", "保存固定配时、绿波方案、人工方案、算法生成方案。", "status", "varchar", "方案状态，如 draft、active、standby、expired。", "启用/回滚方案。", "依赖 plan。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan_phase", "保存某个配时方案下各相位的时长和顺序。", "id", "uuid PK", "方案相位行主键。", "方案明细。", "主键决定整行。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan_phase", "保存某个配时方案下各相位的时长和顺序。", "plan_id", "uuid FK", "所属方案。", "查询方案全部相位。", "联合唯一建议 (plan_id, sequence_no)。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan_phase", "保存某个配时方案下各相位的时长和顺序。", "phase_id", "uuid FK", "对应实际相位。", "确定该步放行方向。", "避免重复存相位名称。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan_phase", "保存某个配时方案下各相位的时长和顺序。", "sequence_no", "integer", "相位顺序。", "固定配时按顺序轮转。", "依赖 plan-phase 关系。"],
      ["信号相位与安全约束", "P1", "signal_timing_plan_phase", "保存某个配时方案下各相位的时长和顺序。", "green_sec", "integer", "该方案下绿灯时长。", "固定配时执行。", "方案明细属性。"],
      ["信号相位与安全约束", "P0", "safety_constraint", "保存安全约束配置，所有策略下发前必须校验。", "id", "uuid PK", "约束主键。", "安全层读取。", "主键决定整行。"],
      ["信号相位与安全约束", "P0", "safety_constraint", "保存安全约束配置，所有策略下发前必须校验。", "intersection_id", "uuid FK nullable", "所属路口；为空表示全局默认。", "支持全局约束和路口覆盖。", "若存在全局/局部覆盖，代码层确定优先级。"],
      ["信号相位与安全约束", "P0", "safety_constraint", "保存安全约束配置，所有策略下发前必须校验。", "constraint_type", "varchar", "约束类型，如 min_green、max_green、spillback。", "按类型执行校验器。", "与 intersection_id 可设唯一。"],
      ["信号相位与安全约束", "P0", "safety_constraint", "保存安全约束配置，所有策略下发前必须校验。", "min_value", "double precision", "最小阈值。", "最小绿灯、最小黄灯等。", "依赖约束行。"],
      ["信号相位与安全约束", "P0", "safety_constraint", "保存安全约束配置，所有策略下发前必须校验。", "max_value", "double precision", "最大阈值。", "最大绿灯、最大排队保护等。", "依赖约束行。"],
      ["信号相位与安全约束", "P1", "safety_constraint", "保存安全约束配置，所有策略下发前必须校验。", "config_payload", "jsonb", "复杂约束参数。", "冲突矩阵、溢出阈值、例外规则。", "复杂可变配置保留 JSONB，避免过早拆分。"],
      ["信号相位与安全约束", "P0", "phase_transition_rule", "保存相位切换合法性和过渡时间。", "id", "uuid PK", "切换规则主键。", "安全层判断是否允许跳相。", "主键决定整行。"],
      ["信号相位与安全约束", "P0", "phase_transition_rule", "保存相位切换合法性和过渡时间。", "intersection_id", "uuid FK", "所属路口。", "查询某路口切换矩阵。", "联合唯一建议 (intersection_id, from_phase_id, to_phase_id)。"],
      ["信号相位与安全约束", "P0", "phase_transition_rule", "保存相位切换合法性和过渡时间。", "from_phase_id", "uuid FK", "原相位。", "判断切换起点。", "依赖规则。"],
      ["信号相位与安全约束", "P0", "phase_transition_rule", "保存相位切换合法性和过渡时间。", "to_phase_id", "uuid FK", "目标相位。", "判断切换终点。", "依赖规则。"],
      ["信号相位与安全约束", "P0", "phase_transition_rule", "保存相位切换合法性和过渡时间。", "allowed", "boolean", "是否允许直接切换。", "非法切换被安全层拒绝或插入过渡。", "依赖候选键。"],
      ["信号相位与安全约束", "P0", "phase_transition_rule", "保存相位切换合法性和过渡时间。", "transition_yellow_sec", "integer", "切换黄灯时间。", "下发前插入过渡相位。", "规则属性。"],
      ["信号相位与安全约束", "P0", "phase_transition_rule", "保存相位切换合法性和过渡时间。", "transition_all_red_sec", "integer", "切换全红时间。", "冲突相位保护。", "规则属性。"],
    ],
  },
  {
    sheet: "03_仿真状态",
    description: "保存仿真会话、帧级指标、道路/车道/路口/车辆状态快照，支持大屏、历史分析和策略复盘。",
    rows: [
      ["仿真会话与状态快照", "P0", "simulation_session", "记录每次仿真运行，所有帧、决策、指标都关联到 session。", "id", "uuid PK", "仿真会话主键。", "关联 frame、decision、emergency_event。", "主键决定整行。"],
      ["仿真会话与状态快照", "P0", "simulation_session", "记录每次仿真运行，所有帧、决策、指标都关联到 session。", "sid", "varchar unique", "运行时仿真 ID，如 run_xxx。", "前端 WebSocket、CityFlow API 使用。", "业务候选键。"],
      ["仿真会话与状态快照", "P0", "simulation_session", "记录每次仿真运行，所有帧、决策、指标都关联到 session。", "scene_id", "uuid FK", "所用场景。", "回放时加载对应路网。", "依赖 session。"],
      ["仿真会话与状态快照", "P0", "simulation_session", "记录每次仿真运行，所有帧、决策、指标都关联到 session。", "controller_type", "varchar", "控制策略，如 fixed-time、max-pressure、traffic-r、hybrid。", "策略对比和筛选。", "会话级默认策略。"],
      ["仿真会话与状态快照", "P1", "simulation_session", "记录每次仿真运行，所有帧、决策、指标都关联到 session。", "speed", "double precision", "仿真倍速。", "分析不同压测速度。", "依赖 session。"],
      ["仿真会话与状态快照", "P0", "simulation_session", "记录每次仿真运行，所有帧、决策、指标都关联到 session。", "status", "varchar", "会话状态，如 created、running、paused、finished。", "大屏显示和控制按钮状态。", "依赖 session。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "id", "uuid PK", "帧主键。", "状态快照和指标关联。", "主键决定整行。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "session_id", "uuid FK", "所属仿真会话。", "按会话查询历史帧。", "联合唯一建议 (session_id, seq)。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "seq", "bigint", "帧序号。", "回放、趋势排序。", "与 session_id 组成候选键。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "sim_time", "double precision", "仿真时间。", "按仿真时间对齐决策和车辆状态。", "依赖帧。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "vehicle_count", "integer", "当前路网车辆数。", "大屏指标、Agent 查询。", "派生指标，可存快照事实。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "queue_count", "integer", "当前总排队数。", "拥堵趋势、策略效果评估。", "派生指标，可存快照事实。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "avg_speed", "double precision", "平均速度。", "策略对比。", "帧事实。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "avg_wait", "double precision", "平均等待时间。", "核心评价指标。", "帧事实。"],
      ["仿真会话与状态快照", "P0", "simulation_frame", "保存全局帧级指标，不存整帧大 JSON，便于趋势查询。", "throughput", "integer", "已完成车辆数或通行量。", "策略有效性评估。", "帧事实。"],
      ["仿真会话与状态快照", "P0", "road_state_snapshot", "保存道路级历史状态，用于道路详情、拥堵诊断、Agent 查询。", "id", "uuid PK", "道路快照主键。", "道路历史记录。", "主键决定整行。"],
      ["仿真会话与状态快照", "P0", "road_state_snapshot", "保存道路级历史状态，用于道路详情、拥堵诊断、Agent 查询。", "frame_id", "uuid FK", "所属帧。", "按帧定位状态。", "联合唯一建议 (frame_id, road_id)。"],
      ["仿真会话与状态快照", "P0", "road_state_snapshot", "保存道路级历史状态，用于道路详情、拥堵诊断、Agent 查询。", "road_id", "uuid FK", "道路。", "道路详情和拥堵分析。", "与 frame_id 组成候选键。"],
      ["仿真会话与状态快照", "P0", "road_state_snapshot", "保存道路级历史状态，用于道路详情、拥堵诊断、Agent 查询。", "vehicle_count", "integer", "道路车辆数。", "道路拥堵判断。", "快照事实。"],
      ["仿真会话与状态快照", "P0", "road_state_snapshot", "保存道路级历史状态，用于道路详情、拥堵诊断、Agent 查询。", "queue_count", "integer", "道路排队车辆数。", "MaxPressure、Agent 诊断。", "快照事实。"],
      ["仿真会话与状态快照", "P0", "road_state_snapshot", "保存道路级历史状态，用于道路详情、拥堵诊断、Agent 查询。", "avg_speed", "double precision", "道路平均速度。", "判断 slow/free/jammed。", "快照事实。"],
      ["仿真会话与状态快照", "P0", "road_state_snapshot", "保存道路级历史状态，用于道路详情、拥堵诊断、Agent 查询。", "level", "varchar", "拥堵等级。", "前端道路颜色。", "可由指标推导；保存为当时分类结果。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "id", "uuid PK", "车道快照主键。", "模型输入审计。", "主键决定整行。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "frame_id", "uuid FK", "所属帧。", "回放某帧模型输入。", "联合唯一建议 (frame_id, lane_id)。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "lane_id", "uuid FK", "车道。", "精确到 lane 的排队和 cell 状态。", "与 frame_id 组成候选键。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "queue_len", "integer", "车道排队长度。", "Traffic-R prompt、MaxPressure 压力。", "快照事实。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "vehicle_count", "integer", "车道车辆数。", "拥堵诊断。", "快照事实。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "avg_wait_time", "double precision", "车道平均等待时间。", "策略效果和 Agent 解释。", "快照事实。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "cell_1", "integer", "近路口第 1 段车辆数。", "对齐官方 lane-level cells 输入。", "固定 4 段时拆列便于查询。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "cell_2", "integer", "第 2 段车辆数。", "Traffic-R 输入。", "同上。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "cell_3", "integer", "第 3 段车辆数。", "Traffic-R 输入。", "同上。"],
      ["仿真会话与状态快照", "P0", "lane_state_snapshot", "保存 Traffic-R lane-level 输入所需状态，支持模型审计和复盘。", "cell_4", "integer", "第 4 段车辆数。", "Traffic-R 输入。", "同上。"],
      ["仿真会话与状态快照", "P0", "intersection_state_snapshot", "保存路口级状态，用于大屏、路口详情、策略诊断。", "id", "uuid PK", "路口快照主键。", "路口历史状态。", "主键决定整行。"],
      ["仿真会话与状态快照", "P0", "intersection_state_snapshot", "保存路口级状态，用于大屏、路口详情、策略诊断。", "frame_id", "uuid FK", "所属帧。", "按帧查询。", "联合唯一建议 (frame_id, intersection_id)。"],
      ["仿真会话与状态快照", "P0", "intersection_state_snapshot", "保存路口级状态，用于大屏、路口详情、策略诊断。", "intersection_id", "uuid FK", "路口。", "路口详情页。", "与 frame_id 组成候选键。"],
      ["仿真会话与状态快照", "P0", "intersection_state_snapshot", "保存路口级状态，用于大屏、路口详情、策略诊断。", "queue_count", "integer", "路口进口总排队。", "拥堵诊断。", "快照事实。"],
      ["仿真会话与状态快照", "P0", "intersection_state_snapshot", "保存路口级状态，用于大屏、路口详情、策略诊断。", "avg_wait", "double precision", "路口平均等待。", "策略效果。", "快照事实。"],
      ["仿真会话与状态快照", "P0", "intersection_state_snapshot", "保存路口级状态，用于大屏、路口详情、策略诊断。", "level", "varchar", "路口拥堵等级。", "前端路口颜色。", "保存当时分类。"],
      ["仿真会话与状态快照", "P0", "intersection_state_snapshot", "保存路口级状态，用于大屏、路口详情、策略诊断。", "current_phase_id", "uuid FK", "当前相位。", "前端信号灯真实状态、Agent 解释。", "引用 signal_phase，避免重复 phaseCode。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "id", "uuid PK", "车辆状态快照主键。", "车辆轨迹回放。", "主键决定整行。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "frame_id", "uuid FK", "所属帧。", "按帧查询车辆。", "联合唯一建议 (frame_id, vehicle_id)。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "vehicle_id", "varchar", "CityFlow 车辆 ID。", "跨帧追踪车辆。", "与 frame_id 组成候选键。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "road_id", "uuid FK", "车辆所在道路。", "前端定位和道路车辆查询。", "快照事实。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "lane_id", "uuid FK nullable", "车辆所在车道。", "车道级轨迹分析。", "可为空，取决于 CityFlow 返回。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "x", "double precision", "渲染坐标 x。", "历史回放。", "快照事实。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "y", "double precision", "渲染坐标 y。", "历史回放。", "快照事实。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "angle", "double precision", "车辆朝向。", "车辆图标旋转。", "快照事实。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "speed", "double precision", "车辆速度。", "停滞检测、动画速度。", "快照事实。"],
      ["仿真会话与状态快照", "P1", "vehicle_state_snapshot", "保存车辆轨迹；数据量大，建议采样保存，EV 全量保存。", "vehicle_type", "varchar", "车辆类型，如 normal、ambulance、fire_truck。", "应急车辆筛选。", "快照属性。"],
    ],
  },
  {
    sheet: "04_策略审计",
    description: "保存策略统一输出、模型推理日志、MaxPressure 评分、安全层事件和降级记录，是验证 Traffic-R 有效性的核心证据。",
    rows: [
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "id", "uuid PK", "决策主键。", "关联 trace、safety_event、max_pressure_score。", "主键决定整行。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "session_id", "uuid FK", "所属仿真会话。", "按会话统计策略效果。", "依赖 decision。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "intersection_id", "uuid FK", "被控制路口。", "路口决策追踪。", "依赖 decision。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "sim_time", "double precision", "决策对应仿真时间。", "与 frame 对齐。", "依赖 decision。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "controller_type", "varchar", "策略来源，如 traffic-r、max-pressure、fixed-time、hybrid。", "策略对比和 Agent 解释。", "依赖 decision。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "requested_phase_id", "uuid FK nullable", "策略原始请求相位。", "判断是否被安全层改写。", "引用 signal_phase。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "final_phase_id", "uuid FK", "最终下发相位。", "前端/Agent 解释最终执行。", "引用 signal_phase。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "duration_sec", "integer", "建议持续时间。", "策略下发和安全层检查。", "依赖 decision。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "status", "varchar", "决策状态，如 accepted、rewritten、rejected、fallback。", "Agent 回答为什么没执行模型。", "依赖 decision。"],
      ["策略调度与模型审计", "P0", "control_decision", "统一保存 Traffic-R、MaxPressure、Fixed-Time、Hybrid 的最终控制决策。", "reason", "text", "决策理由。", "大屏展示和审计。", "非结构化说明，不作为查询主键。"],
      ["策略调度与模型审计", "P0", "control_decision_trace", "保存决策链路，如模型输出、安全层改写、fallback 原因。", "id", "uuid PK", "决策链路主键。", "多阶段追踪。", "主键决定整行。"],
      ["策略调度与模型审计", "P0", "control_decision_trace", "保存决策链路，如模型输出、安全层改写、fallback 原因。", "decision_id", "uuid FK", "所属最终决策。", "查询某次决策全过程。", "依赖 trace。"],
      ["策略调度与模型审计", "P0", "control_decision_trace", "保存决策链路，如模型输出、安全层改写、fallback 原因。", "stage", "varchar", "阶段，如 model_request、model_response、safety_check、apply_action。", "按阶段解释。", "依赖 trace。"],
      ["策略调度与模型审计", "P1", "control_decision_trace", "保存决策链路，如模型输出、安全层改写、fallback 原因。", "input_payload", "jsonb", "该阶段输入。", "审计模型/安全层输入。", "输入结构变化快，JSONB 合理。"],
      ["策略调度与模型审计", "P1", "control_decision_trace", "保存决策链路，如模型输出、安全层改写、fallback 原因。", "output_payload", "jsonb", "该阶段输出。", "审计执行结果。", "JSONB 保留完整证据。"],
      ["策略调度与模型审计", "P1", "control_decision_trace", "保存决策链路，如模型输出、安全层改写、fallback 原因。", "message", "text", "阶段说明或异常。", "Agent 自然语言解释依据。", "依赖 trace。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "id", "uuid PK", "推理日志主键。", "Traffic-R 审计。", "主键决定整行。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "session_id", "uuid FK", "所属仿真会话。", "按会话统计推理耗时和有效率。", "依赖日志。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "sim_time", "double precision", "请求发生的仿真时间。", "和决策周期对齐。", "依赖日志。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "request_payload", "jsonb", "发送给 Traffic-R 的结构化请求。", "复现实验、调试 lane-level 输入。", "模型输入结构变化快，JSONB。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "prompt_text", "text", "最终 prompt 文本。", "排查模型为什么输出异常。", "文本证据。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "raw_output", "text", "模型原始输出。", "验证是否被截断、是否格式错误。", "文本证据。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "parsed_phase_code", "varchar", "解析出的相位编码。", "统计输出分布。", "依赖日志。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "valid", "boolean", "输出是否合法。", "fallback 判断、模型有效率。", "依赖日志。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "latency_ms", "integer", "推理耗时。", "决策周期调参。", "依赖日志。"],
      ["策略调度与模型审计", "P0", "traffic_r_inference_log", "保存 Traffic-R 请求与响应，用于分析模型是否真的有效。", "error_message", "text", "异常信息。", "超时、解析失败排查。", "可为空。"],
      ["策略调度与模型审计", "P0", "max_pressure_score", "保存 MaxPressure 各候选相位得分，解释为什么选某相位。", "id", "uuid PK", "评分主键。", "MaxPressure 解释。", "主键决定整行。"],
      ["策略调度与模型审计", "P0", "max_pressure_score", "保存 MaxPressure 各候选相位得分，解释为什么选某相位。", "decision_id", "uuid FK", "所属决策。", "查询某次 MaxPressure 各相位分数。", "联合唯一建议 (decision_id, phase_id)。"],
      ["策略调度与模型审计", "P0", "max_pressure_score", "保存 MaxPressure 各候选相位得分，解释为什么选某相位。", "phase_id", "uuid FK", "候选相位。", "解释候选得分。", "与 decision_id 组成候选键。"],
      ["策略调度与模型审计", "P0", "max_pressure_score", "保存 MaxPressure 各候选相位得分，解释为什么选某相位。", "pressure_score", "double precision", "压力得分。", "选择最大压力相位。", "依赖评分行。"],
      ["策略调度与模型审计", "P1", "max_pressure_score", "保存 MaxPressure 各候选相位得分，解释为什么选某相位。", "detail_payload", "jsonb", "上游/下游压力明细。", "Agent 解释 pressure 来源。", "明细结构可变，JSONB。"],
      ["策略调度与模型审计", "P0", "strategy_fallback_event", "记录 Traffic-R 超时、无效输出、连续失败后降级 MaxPressure。", "id", "uuid PK", "降级事件主键。", "Agent 查询降级原因。", "主键决定整行。"],
      ["策略调度与模型审计", "P0", "strategy_fallback_event", "记录 Traffic-R 超时、无效输出、连续失败后降级 MaxPressure。", "session_id", "uuid FK", "所属会话。", "按会话统计 fallback 次数。", "依赖事件。"],
      ["策略调度与模型审计", "P0", "strategy_fallback_event", "记录 Traffic-R 超时、无效输出、连续失败后降级 MaxPressure。", "intersection_id", "uuid FK", "发生降级的路口。", "定位不稳定路口。", "依赖事件。"],
      ["策略调度与模型审计", "P0", "strategy_fallback_event", "记录 Traffic-R 超时、无效输出、连续失败后降级 MaxPressure。", "from_strategy", "varchar", "原策略。", "如 traffic-r。", "事件属性。"],
      ["策略调度与模型审计", "P0", "strategy_fallback_event", "记录 Traffic-R 超时、无效输出、连续失败后降级 MaxPressure。", "to_strategy", "varchar", "目标策略。", "如 max-pressure。", "事件属性。"],
      ["策略调度与模型审计", "P0", "strategy_fallback_event", "记录 Traffic-R 超时、无效输出、连续失败后降级 MaxPressure。", "reason", "text", "降级原因。", "解释模型不可用。", "事件属性。"],
      ["策略调度与模型审计", "P0", "strategy_fallback_event", "记录 Traffic-R 超时、无效输出、连续失败后降级 MaxPressure。", "sim_time", "double precision", "发生仿真时间。", "对齐决策周期。", "事件属性。"],
      ["策略调度与模型审计", "P0", "safety_constraint_event", "记录安全层拦截、延迟、改写，是 Agent 解释信号控制的核心证据。", "id", "uuid PK", "安全事件主键。", "安全审计。", "主键决定整行。"],
      ["策略调度与模型审计", "P0", "safety_constraint_event", "记录安全层拦截、延迟、改写，是 Agent 解释信号控制的核心证据。", "decision_id", "uuid FK", "关联控制决策。", "解释某决策是否被改写。", "依赖事件。"],
      ["策略调度与模型审计", "P0", "safety_constraint_event", "记录安全层拦截、延迟、改写，是 Agent 解释信号控制的核心证据。", "constraint_type", "varchar", "触发约束类型。", "筛选最小绿、冲突相位等。", "事件属性。"],
      ["策略调度与模型审计", "P0", "safety_constraint_event", "记录安全层拦截、延迟、改写，是 Agent 解释信号控制的核心证据。", "action", "varchar", "处理动作，如 pass、delay、rewrite、reject。", "解释安全层行为。", "事件属性。"],
      ["策略调度与模型审计", "P0", "safety_constraint_event", "记录安全层拦截、延迟、改写，是 Agent 解释信号控制的核心证据。", "before_phase_id", "uuid FK nullable", "改写前相位。", "比较模型建议和最终动作。", "引用 signal_phase。"],
      ["策略调度与模型审计", "P0", "safety_constraint_event", "记录安全层拦截、延迟、改写，是 Agent 解释信号控制的核心证据。", "after_phase_id", "uuid FK nullable", "改写后相位。", "解释最终相位。", "引用 signal_phase。"],
      ["策略调度与模型审计", "P0", "safety_constraint_event", "记录安全层拦截、延迟、改写，是 Agent 解释信号控制的核心证据。", "reason", "text", "安全处理原因。", "Agent 回答为什么没有执行模型建议。", "事件属性。"],
    ],
  },
  {
    sheet: "05_区域应急Agent",
    description: "保存混合控制区域、应急绿波、Agent 工具调用、告警和服务健康，支撑真实系统运维与审计。",
    rows: [
      ["区域混合控制", "P1", "control_region", "保存 Traffic-R core、MaxPressure boundary、复杂区域等连续控制区域。", "id", "uuid PK", "控制区域主键。", "区域策略配置。", "主键决定整行。"],
      ["区域混合控制", "P1", "control_region", "保存 Traffic-R core、MaxPressure boundary、复杂区域等连续控制区域。", "scene_id", "uuid FK", "所属场景。", "不同路网独立划分区域。", "联合唯一建议 (scene_id, region_code)。"],
      ["区域混合控制", "P1", "control_region", "保存 Traffic-R core、MaxPressure boundary、复杂区域等连续控制区域。", "region_code", "varchar", "区域编码。", "配置和前端展示。", "与 scene_id 组成候选键。"],
      ["区域混合控制", "P1", "control_region", "保存 Traffic-R core、MaxPressure boundary、复杂区域等连续控制区域。", "name", "varchar", "区域名称。", "地图图层和 Agent 回答。", "依赖 region。"],
      ["区域混合控制", "P1", "control_region", "保存 Traffic-R core、MaxPressure boundary、复杂区域等连续控制区域。", "controller_type", "varchar", "区域默认控制策略。", "Traffic-R core / MaxPressure boundary。", "区域属性。"],
      ["区域混合控制", "P1", "control_region", "保存 Traffic-R core、MaxPressure boundary、复杂区域等连续控制区域。", "region_type", "varchar", "区域类型，如 core、boundary、complex、external。", "边界协调。", "区域属性。"],
      ["区域混合控制", "P1", "control_region_intersection", "保存路口归属和区域角色。", "region_id", "uuid FK", "所属区域。", "查询区域包含路口。", "复合主键 (region_id, intersection_id)，满足 BCNF。"],
      ["区域混合控制", "P1", "control_region_intersection", "保存路口归属和区域角色。", "intersection_id", "uuid FK", "路口。", "判断路口由哪种策略控制。", "复合主键组成。"],
      ["区域混合控制", "P1", "control_region_intersection", "保存路口归属和区域角色。", "role", "varchar", "角色，如 core、boundary、external。", "边界缓冲和策略路由。", "依赖复合键。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "id", "uuid PK", "应急事件主键。", "关联路线、绿波信号事件。", "主键决定整行。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "session_id", "uuid FK", "所属仿真会话。", "回放应急任务。", "依赖 event。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "event_code", "varchar unique", "应急任务编码。", "人工查询和审计。", "业务候选键。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "vehicle_id", "varchar", "应急车辆业务 ID。", "前端展示。", "事件属性；CityFlow ID 可另存扩展字段。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "vehicle_type", "varchar", "车辆类型，如 ambulance、fire_truck。", "优先级默认值。", "事件属性。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "priority", "integer", "优先级，数字越小越高。", "冲突应急任务排序。", "事件属性。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "status", "varchar", "任务状态，如 pending、active、finished、failed。", "前端绿波状态。", "事件属性。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "start_coord", "jsonb", "起点坐标。", "地图选点回放和重新规划。", "坐标结构可变，JSONB。"],
      ["应急绿波", "P0", "emergency_event", "保存一次应急车辆调度任务。", "end_coord", "jsonb", "终点坐标。", "地图选点回放和重新规划。", "坐标结构可变，JSONB。"],
      ["应急绿波", "P0", "emergency_route_node", "规范化保存应急路线，避免路线只放 JSONB 导致无法查询。", "id", "uuid PK", "路线节点主键。", "应急路线明细。", "主键决定整行。"],
      ["应急绿波", "P0", "emergency_route_node", "规范化保存应急路线，避免路线只放 JSONB 导致无法查询。", "emergency_event_id", "uuid FK", "所属应急任务。", "查询某任务全部路线节点。", "联合唯一建议 (event_id, sequence_no)。"],
      ["应急绿波", "P0", "emergency_route_node", "规范化保存应急路线，避免路线只放 JSONB 导致无法查询。", "sequence_no", "integer", "路线顺序。", "按顺序渲染绿波路线。", "与 event_id 组成候选键。"],
      ["应急绿波", "P0", "emergency_route_node", "规范化保存应急路线，避免路线只放 JSONB 导致无法查询。", "intersection_id", "uuid FK", "经过路口。", "统计影响路口。", "依赖路线节点。"],
      ["应急绿波", "P0", "emergency_route_node", "规范化保存应急路线，避免路线只放 JSONB 导致无法查询。", "road_id", "uuid FK nullable", "进入或离开该节点的道路。", "显示 road route。", "依赖路线节点。"],
      ["应急绿波", "P1", "emergency_route_node", "规范化保存应急路线，避免路线只放 JSONB 导致无法查询。", "planned_arrival_time", "double precision", "预计到达仿真时间。", "绿波提前量计算。", "路线节点属性。"],
      ["应急绿波", "P1", "emergency_route_node", "规范化保存应急路线，避免路线只放 JSONB 导致无法查询。", "actual_arrival_time", "double precision", "实际到达仿真时间。", "评估绿波效果。", "路线节点属性。"],
      ["应急绿波", "P0", "emergency_signal_event", "保存延绿、红灯缩短、恢复普通控制等绿波动作。", "id", "uuid PK", "绿波信号事件主键。", "审计应急优先。", "主键决定整行。"],
      ["应急绿波", "P0", "emergency_signal_event", "保存延绿、红灯缩短、恢复普通控制等绿波动作。", "emergency_event_id", "uuid FK", "所属应急任务。", "查询某任务产生的信号动作。", "依赖事件。"],
      ["应急绿波", "P0", "emergency_signal_event", "保存延绿、红灯缩短、恢复普通控制等绿波动作。", "intersection_id", "uuid FK", "触发路口。", "前端闪烁提示。", "依赖事件。"],
      ["应急绿波", "P0", "emergency_signal_event", "保存延绿、红灯缩短、恢复普通控制等绿波动作。", "sim_time", "double precision", "发生仿真时间。", "回放应急信号调整。", "事件属性。"],
      ["应急绿波", "P0", "emergency_signal_event", "保存延绿、红灯缩短、恢复普通控制等绿波动作。", "action_type", "varchar", "动作类型，如 extend_green、shorten_red、restore。", "Agent 解释绿波动作。", "事件属性。"],
      ["应急绿波", "P0", "emergency_signal_event", "保存延绿、红灯缩短、恢复普通控制等绿波动作。", "phase_id_before", "uuid FK nullable", "动作前相位。", "审计改写。", "引用 signal_phase。"],
      ["应急绿波", "P0", "emergency_signal_event", "保存延绿、红灯缩短、恢复普通控制等绿波动作。", "phase_id_after", "uuid FK nullable", "动作后相位。", "审计最终相位。", "引用 signal_phase。"],
      ["应急绿波", "P0", "emergency_signal_event", "保存延绿、红灯缩短、恢复普通控制等绿波动作。", "reason", "text", "动作原因。", "Agent 解释。", "事件属性。"],
      ["Agent与运维审计", "P1", "agent_conversation", "保存一次 Agent 对话上下文。", "id", "uuid PK", "会话主键。", "关联消息和工具调用。", "主键决定整行。"],
      ["Agent与运维审计", "P1", "agent_conversation", "保存一次 Agent 对话上下文。", "user_id", "uuid nullable", "用户 ID。", "权限和历史对话归属。", "若无用户系统可为空。"],
      ["Agent与运维审计", "P1", "agent_conversation", "保存一次 Agent 对话上下文。", "session_id", "uuid FK nullable", "关联仿真会话。", "让 Agent 回答当前仿真问题。", "对话可不绑定仿真。"],
      ["Agent与运维审计", "P1", "agent_conversation", "保存一次 Agent 对话上下文。", "title", "varchar", "对话标题。", "前端对话列表。", "依赖 conversation。"],
      ["Agent与运维审计", "P1", "agent_message", "保存用户问题和 Agent 回答。", "id", "uuid PK", "消息主键。", "消息记录。", "主键决定整行。"],
      ["Agent与运维审计", "P1", "agent_message", "保存用户问题和 Agent 回答。", "conversation_id", "uuid FK", "所属对话。", "查询对话上下文。", "依赖 message。"],
      ["Agent与运维审计", "P1", "agent_message", "保存用户问题和 Agent 回答。", "role", "varchar", "角色，如 user、assistant、system。", "区分消息来源。", "依赖 message。"],
      ["Agent与运维审计", "P1", "agent_message", "保存用户问题和 Agent 回答。", "content", "text", "消息内容。", "对话展示和审计。", "文本事实。"],
      ["Agent与运维审计", "P1", "agent_tool_call", "记录 Agent 调用了哪些工具，保障可追溯。", "id", "uuid PK", "工具调用主键。", "工具审计。", "主键决定整行。"],
      ["Agent与运维审计", "P1", "agent_tool_call", "记录 Agent 调用了哪些工具，保障可追溯。", "message_id", "uuid FK", "触发该工具调用的 Agent 消息。", "从回答追溯证据。", "依赖调用。"],
      ["Agent与运维审计", "P1", "agent_tool_call", "记录 Agent 调用了哪些工具，保障可追溯。", "tool_name", "varchar", "工具名称。", "统计工具使用和调试选错工具。", "依赖调用。"],
      ["Agent与运维审计", "P1", "agent_tool_call", "记录 Agent 调用了哪些工具，保障可追溯。", "arguments_payload", "jsonb", "工具参数。", "复现 Agent 查询。", "参数结构因工具不同，JSONB。"],
      ["Agent与运维审计", "P1", "agent_tool_call", "记录 Agent 调用了哪些工具，保障可追溯。", "result_payload", "jsonb", "工具结果。", "回答证据来源。", "结果结构因工具不同，JSONB。"],
      ["Agent与运维审计", "P1", "agent_tool_call", "记录 Agent 调用了哪些工具，保障可追溯。", "status", "varchar", "调用状态，如 success、failed。", "Agent 健康诊断。", "依赖调用。"],
      ["Agent与运维审计", "P1", "agent_tool_call", "记录 Agent 调用了哪些工具，保障可追溯。", "latency_ms", "integer", "调用耗时。", "性能分析。", "依赖调用。"],
      ["Agent与运维审计", "P0", "operation_audit_log", "记录人工确认、策略切换、应急任务执行等关键操作。", "id", "uuid PK", "审计日志主键。", "合规审计。", "主键决定整行。"],
      ["Agent与运维审计", "P0", "operation_audit_log", "记录人工确认、策略切换、应急任务执行等关键操作。", "actor_type", "varchar", "操作者类型，如 user、agent、system。", "区分人工和系统动作。", "日志属性。"],
      ["Agent与运维审计", "P0", "operation_audit_log", "记录人工确认、策略切换、应急任务执行等关键操作。", "actor_id", "varchar", "操作者 ID。", "追责和审计。", "日志属性。"],
      ["Agent与运维审计", "P0", "operation_audit_log", "记录人工确认、策略切换、应急任务执行等关键操作。", "operation_type", "varchar", "操作类型，如 approve_dispatch、switch_strategy。", "审计筛选。", "日志属性。"],
      ["Agent与运维审计", "P0", "operation_audit_log", "记录人工确认、策略切换、应急任务执行等关键操作。", "target_type", "varchar", "操作对象类型。", "定位对象。", "日志属性。"],
      ["Agent与运维审计", "P0", "operation_audit_log", "记录人工确认、策略切换、应急任务执行等关键操作。", "target_id", "varchar", "操作对象 ID。", "定位对象。", "日志属性。"],
      ["Agent与运维审计", "P0", "operation_audit_log", "记录人工确认、策略切换、应急任务执行等关键操作。", "request_payload", "jsonb", "操作请求内容。", "复盘人工确认内容。", "结构可变，JSONB。"],
      ["Agent与运维审计", "P0", "operation_audit_log", "记录人工确认、策略切换、应急任务执行等关键操作。", "result_status", "varchar", "执行结果。", "判断操作是否成功。", "日志属性。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "id", "uuid PK", "告警主键。", "告警列表。", "主键决定整行。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "session_id", "uuid FK nullable", "关联仿真会话。", "仿真告警归属。", "可为空表示系统告警。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "alert_type", "varchar", "告警类型。", "分类处理。", "告警属性。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "level", "varchar", "告警级别，如 info、warning、error、emergency。", "前端颜色和处理优先级。", "告警属性。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "target_type", "varchar", "对象类型，如 road、intersection、vehicle、system。", "定位告警对象。", "告警属性。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "target_id", "varchar", "对象 ID。", "跳转详情页。", "告警属性。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "title", "varchar", "告警标题。", "前端展示。", "告警属性。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "description", "text", "告警描述。", "Agent 解释。", "告警属性。"],
      ["告警与系统健康", "P0", "alert_event", "保存拥堵、模型超时、信号异常、CityFlow 异常等告警。", "status", "varchar", "状态，如 open、acknowledged、resolved。", "告警处理流程。", "告警属性。"],
      ["告警与系统健康", "P1", "service_health_snapshot", "保存 Spring Boot、CityFlow、Traffic-R、数据库、WebSocket 健康状态。", "id", "uuid PK", "健康快照主键。", "运维诊断。", "主键决定整行。"],
      ["告警与系统健康", "P1", "service_health_snapshot", "保存 Spring Boot、CityFlow、Traffic-R、数据库、WebSocket 健康状态。", "service_name", "varchar", "服务名称。", "查询 CityFlow/Traffic-R 是否在线。", "与 checked_at 可建索引。"],
      ["告警与系统健康", "P1", "service_health_snapshot", "保存 Spring Boot、CityFlow、Traffic-R、数据库、WebSocket 健康状态。", "status", "varchar", "状态，如 UP、DOWN、DEGRADED。", "Agent 运维回答。", "快照事实。"],
      ["告警与系统健康", "P1", "service_health_snapshot", "保存 Spring Boot、CityFlow、Traffic-R、数据库、WebSocket 健康状态。", "latency_ms", "integer", "健康检查耗时。", "性能监控。", "快照事实。"],
      ["告警与系统健康", "P1", "service_health_snapshot", "保存 Spring Boot、CityFlow、Traffic-R、数据库、WebSocket 健康状态。", "detail_payload", "jsonb", "服务返回详情。", "排查错误。", "不同服务结构不同，JSONB。"],
      ["告警与系统健康", "P1", "service_health_snapshot", "保存 Spring Boot、CityFlow、Traffic-R、数据库、WebSocket 健康状态。", "checked_at", "timestamp", "检查时间。", "按时间查看健康趋势。", "快照时间索引。"],
    ],
  },
];

const overviewRows = [];
for (const domain of domains.filter((d) => d.sheet !== "00_总览")) {
  const byTable = new Map();
  for (const row of domain.rows) {
    const [businessDomain, priority, tableName, tableUsage] = row;
    if (!byTable.has(tableName)) {
      byTable.set(tableName, {
        businessDomain,
        priority,
        tableName,
        tableUsage,
        fieldCount: 0,
        sheet: domain.sheet,
      });
    }
    byTable.get(tableName).fieldCount += 1;
  }
  for (const item of byTable.values()) {
    overviewRows.push([
      item.businessDomain,
      item.priority,
      item.tableName,
      item.tableUsage,
      item.fieldCount,
      item.sheet,
      item.priority === "P0" ? "必须优先落库，支撑核心仿真、策略、审计或安全闭环。" : "增强或扩展能力，可在核心链路稳定后实现。",
    ]);
  }
}

domains[0].rows = overviewRows;

const workbook = Workbook.create();
workbook.comments.setSelf({ displayName: "Codex" });

const palette = {
  title: "#17324D",
  header: "#1F6F8B",
  subHeader: "#E8F3F8",
  p0: "#FCE8E6",
  p1: "#E8F0FE",
  grid: "#D8E2EA",
  note: "#F7FAFC",
};

function styleTitle(sheet, title, subtitle, lastColLetter) {
  sheet.showGridLines = false;
  const titleRange = sheet.getRange(`A1:${lastColLetter}1`);
  titleRange.merge();
  titleRange.values = [[title]];
  titleRange.format = {
    fill: palette.title,
    font: { bold: true, color: "#FFFFFF", size: 15 },
    horizontalAlignment: "left",
    verticalAlignment: "center",
  };
  titleRange.format.rowHeight = 30;

  const subtitleRange = sheet.getRange(`A2:${lastColLetter}2`);
  subtitleRange.merge();
  subtitleRange.values = [[subtitle]];
  subtitleRange.format = {
    fill: palette.note,
    font: { color: "#334155", size: 10 },
    wrapText: true,
    verticalAlignment: "top",
  };
  subtitleRange.format.rowHeight = 38;
}

function applyPriorityFormatting(sheet, startRow, rowCount, priorityColLetter = "B") {
  const range = sheet.getRange(`${priorityColLetter}${startRow}:${priorityColLetter}${startRow + rowCount - 1}`);
  range.conditionalFormats.add("containsText", {
    text: "P0",
    format: { fill: palette.p0, font: { bold: true, color: "#B42318" } },
  });
  range.conditionalFormats.add("containsText", {
    text: "P1",
    format: { fill: palette.p1, font: { bold: true, color: "#1A56DB" } },
  });
}

function addDomainSheet(domain) {
  const sheet = workbook.worksheets.add(domain.sheet);
  const isOverview = domain.sheet === "00_总览";
  const header = isOverview
    ? ["业务域", "完成优先级", "表名", "表使用场景", "字段数", "所在Sheet", "实施建议"]
    : columns;
  const lastColLetter = isOverview ? "G" : "I";
  styleTitle(sheet, domain.sheet, domain.description, lastColLetter);
  sheet.getRangeByIndexes(2, 0, 1, header.length).values = [header];
  sheet.getRangeByIndexes(3, 0, domain.rows.length, header.length).values = domain.rows;
  const usedRows = domain.rows.length + 3;
  const tableRange = `A3:${lastColLetter}${usedRows}`;
  const tableName = domain.sheet.replace(/[^A-Za-z0-9_]/g, "_") + "_Table";
  const table = sheet.tables.add(tableRange, true, tableName);
  table.style = "TableStyleMedium2";
  table.showFilterButton = true;
  sheet.freezePanes.freezeRows(3);

  sheet.getRange(`A3:${lastColLetter}3`).format = {
    fill: palette.header,
    font: { bold: true, color: "#FFFFFF" },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    wrapText: true,
  };
  sheet.getRange(`A4:${lastColLetter}${usedRows}`).format = {
    verticalAlignment: "top",
    wrapText: true,
    borders: { preset: "inside", style: "thin", color: palette.grid },
  };
  sheet.getRange(`A3:${lastColLetter}${usedRows}`).format.borders = {
    preset: "outside",
    style: "medium",
    color: "#A5B4C3",
  };

  applyPriorityFormatting(sheet, 4, domain.rows.length);
  if (isOverview) {
    const widths = [20, 11, 30, 52, 8, 18, 48];
    widths.forEach((w, i) => { sheet.getRangeByIndexes(0, i, 1, 1).format.columnWidth = w; });
  } else {
    const widths = [18, 11, 26, 44, 24, 18, 48, 48, 42];
    widths.forEach((w, i) => { sheet.getRangeByIndexes(0, i, 1, 1).format.columnWidth = w; });
  }
  sheet.getRange(`A4:${lastColLetter}${usedRows}`).format.rowHeight = 54;
  sheet.getRange(`A1:${lastColLetter}2`).format.borders = { preset: "outside", style: "thin", color: palette.grid };
  return sheet;
}

for (const domain of domains) {
  addDomainSheet(domain);
}

const sourceSheet = workbook.worksheets.add("99_设计说明");
styleTitle(
  sourceSheet,
  "99_设计说明",
  "本工作簿根据当前项目业务功能、已有 DATABASE_SCHEMA.md、仓库文档和接口约定整理。核心原则：静态路网结构化、动态状态快照化、算法和安全事件可追溯、Agent 工具调用可审计。",
  "E",
);
const notes = [
  ["设计原则", "说明"],
  ["3NF", "非主属性只依赖候选键，不通过其他非主属性传递依赖。示例：相位放行 roadLink 拆到 signal_phase_road_link，不把 roadLink 列表冗余在 signal_phase 中。"],
  ["BCNF", "每个非平凡函数依赖的决定因素应为超键。示例：region-intersection 关系用复合主键，role 依赖该复合键。"],
  ["JSONB 使用边界", "仅用于结构变化快或原始审计证据，例如 Traffic-R prompt、工具调用参数、服务健康详情；稳定实体关系应拆表。"],
  ["数据量建议", "vehicle_state_snapshot 数据量最大，建议普通车辆采样保存，应急车辆全量保存；lane/road/intersection 快照可按帧或按秒聚合保存。"],
  ["大屏数据", "dashboard_* 和 analytics_* 建议作为演示/物化视图层，不作为长期核心事实表。"],
  ["接口同步", "新增或修改表若影响 API、DTO、WebSocket 字段、Agent 工具参数，应同步更新接口文档。"],
];
sourceSheet.getRangeByIndexes(2, 0, notes.length, 2).values = notes;
const notesTable = sourceSheet.tables.add(`A3:B${notes.length + 2}`, true, "DesignNotesTable");
notesTable.style = "TableStyleMedium2";
sourceSheet.freezePanes.freezeRows(3);
sourceSheet.getRange("A:A").format.columnWidth = 22;
sourceSheet.getRange("B:B").format.columnWidth = 96;
sourceSheet.getRange(`A4:B${notes.length + 2}`).format = { wrapText: true, verticalAlignment: "top" };
sourceSheet.getRange(`A4:B${notes.length + 2}`).format.rowHeight = 52;

await fs.mkdir(outputDir, { recursive: true });

for (const sheetName of domains.map((d) => d.sheet).concat("99_设计说明")) {
  const preview = await workbook.render({
    sheetName,
    autoCrop: "all",
    scale: 1,
    format: "png",
  });
  await fs.writeFile(
    path.join(outputDir, `${sheetName}.png`),
    new Uint8Array(await preview.arrayBuffer()),
  );
}

const overviewInspect = await workbook.inspect({
  kind: "table",
  sheetId: "00_总览",
  range: "A1:G20",
  include: "values",
  tableMaxRows: 20,
  tableMaxCols: 7,
  maxChars: 4000,
});
console.log(overviewInspect.ndjson);

const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
  options: { useRegex: true, maxResults: 300 },
  summary: "final formula error scan",
  maxChars: 2000,
});
console.log(errors.ndjson);

const xlsx = await SpreadsheetFile.exportXlsx(workbook);
await xlsx.save(outputPath);
console.log(outputPath);
