create table intersections (
    id uuid default random_uuid() primary key,
    code varchar(32) not null unique,
    name varchar(128) not null,
    district varchar(64),
    longitude decimal(10, 6) not null,
    latitude decimal(10, 6) not null,
    status varchar(32) not null,
    metadata text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table dashboard_intersection (
    id varchar(32) primary key,
    name varchar(128) not null,
    x double precision not null,
    y double precision not null,
    lng double precision not null,
    lat double precision not null,
    row_no integer not null,
    col_no integer not null,
    current_phase varchar(64) not null,
    green_remain integer not null,
    queue_length integer not null,
    average_delay double precision not null,
    congestion_index double precision not null,
    device_status varchar(32) not null
);

create table dashboard_road (
    id varchar(32) primary key,
    from_intersection_id varchar(32) not null,
    to_intersection_id varchar(32) not null,
    name varchar(128) not null,
    flow integer not null,
    speed double precision not null,
    queue_length double precision not null,
    congestion_index double precision not null,
    lane_count integer not null,
    direction varchar(16) not null,
    path_json text not null
);

create table dashboard_vehicle (
    id varchar(32) primary key,
    road_id varchar(32) not null,
    progress double precision not null,
    speed double precision not null,
    vehicle_type varchar(32) not null,
    lane_index integer not null
);

create table dashboard_emergency_vehicle (
    id varchar(32) primary key,
    vehicle_type varchar(32) not null,
    current_intersection_id varchar(32) not null,
    destination varchar(128) not null,
    green_wave_active boolean not null,
    eta integer not null
);

create table dashboard_emergency_route (
    sequence_no integer primary key,
    intersection_id varchar(32) not null
);

create table dashboard_alert (
    id varchar(32) primary key,
    type varchar(64) not null,
    level varchar(32) not null,
    title varchar(256) not null,
    location varchar(256) not null,
    event_time varchar(32) not null,
    intersection_id varchar(32),
    acknowledged boolean not null
);

create table dashboard_statistics (
    id integer primary key,
    total_flow integer not null,
    average_speed double precision not null,
    average_wait_time double precision not null,
    congestion_index double precision not null,
    congested_road_count integer not null,
    optimized_intersection_count integer not null,
    emergency_vehicle_count integer not null,
    device_online_rate double precision not null,
    today_alert_count integer not null,
    green_wave_count integer not null
);

create table dashboard_compare_metric (
    metric_key varchar(64) primary key,
    name varchar(64) not null,
    traditional_value double precision not null,
    ai_value double precision not null,
    unit varchar(16) not null,
    direction varchar(16) not null
);

create table dashboard_congestion_trend (
    sequence_no integer primary key,
    time_label varchar(16) not null,
    metric_value double precision not null
);

create table dashboard_assistant_reply (
    keyword varchar(32) primary key,
    reply text not null
);

insert into dashboard_intersection (
    id, name, x, y, lng, lat, row_no, col_no, current_phase, green_remain,
    queue_length, average_delay, congestion_index, device_status
) values
('DB-A01', '西藏中路-南京东路', 0.731, 0.163, 121.475600, 31.235600, 1, 1, 'eastwest_straight', 28, 9, 56.0, 68.0, 'online'),
('DB-A02', '黄陂北路-南京西路', 0.600, 0.241, 121.471000, 31.233500, 1, 2, 'northsouth_left', 14, 12, 42.0, 45.0, 'online'),
('DB-A03', '茂名北路-南京西路', 0.320, 0.304, 121.461200, 31.231800, 1, 3, 'eastwest_straight', 42, 24, 67.0, 87.0, 'online'),
('DB-A04', '常德路-南京西路', 0.051, 0.352, 121.451800, 31.230500, 1, 4, 'eastwest_left', 18, 7, 31.0, 32.0, 'offline'),
('DB-A05', '西藏南路-淮海东路', 0.771, 0.463, 121.477000, 31.227500, 2, 1, 'northsouth_straight', 35, 18, 58.0, 72.0, 'online'),
('DB-A06', '黄陂南路-淮海中路', 0.643, 0.537, 121.472500, 31.225500, 2, 2, 'eastwest_straight', 0, 31, 78.0, 92.0, 'fault'),
('DB-A07', '瑞金二路-淮海中路', 0.377, 0.600, 121.463200, 31.223800, 2, 3, 'northsouth_left', 22, 16, 48.0, 55.0, 'online'),
('DB-A08', '常熟路-淮海中路', 0.086, 0.648, 121.453000, 31.222500, 2, 4, 'all_red', 3, 5, 25.0, 22.0, 'online'),
('DB-A09', '西藏南路-建国东路', 0.794, 0.711, 121.477800, 31.220800, 3, 1, 'eastwest_straight', 50, 11, 45.0, 48.0, 'online'),
('DB-A10', '黄陂南路-建国东路', 0.657, 0.778, 121.473000, 31.219000, 3, 2, 'northsouth_straight', 32, 14, 53.0, 64.0, 'online'),
('DB-A11', '瑞金二路-建国中路', 0.400, 0.889, 121.464000, 31.216000, 3, 3, 'eastwest_left', 25, 10, 39.0, 40.0, 'fault'),
('DB-A12', '襄阳南路-建国西路', 0.109, 0.944, 121.453800, 31.214500, 3, 4, 'northsouth_left', 40, 6, 29.0, 30.0, 'offline');

insert into intersections (code, name, district, longitude, latitude, status, metadata)
select id, name, '上海市中心演示区', lng, lat, device_status,
       '{"source":"demo","scene":"dashboard"}'
from dashboard_intersection;

insert into dashboard_road (
    id, from_intersection_id, to_intersection_id, name, flow, speed, queue_length,
    congestion_index, lane_count, direction, path_json
) values
('DB-R01', 'DB-A01', 'DB-A02', '南京东路', 1820, 45.0, 120.0, 65.0, 3, 'two-way', '[[121.4756,31.2356],[121.4734,31.2348],[121.4710,31.2335]]'),
('DB-R02', 'DB-A02', 'DB-A03', '南京西路东段', 2240, 38.0, 180.0, 78.0, 3, 'two-way', '[[121.4710,31.2335],[121.4662,31.2328],[121.4612,31.2318]]'),
('DB-R03', 'DB-A03', 'DB-A04', '南京西路西段', 1560, 52.0, 85.0, 42.0, 3, 'two-way', '[[121.4612,31.2318],[121.4565,31.2312],[121.4518,31.2305]]'),
('DB-R05', 'DB-A05', 'DB-A06', '淮海东路', 1380, 48.0, 95.0, 52.0, 3, 'two-way', '[[121.4770,31.2275],[121.4748,31.2266],[121.4725,31.2255]]'),
('DB-R06', 'DB-A06', 'DB-A07', '淮海中路中段', 2600, 22.0, 280.0, 94.0, 3, 'two-way', '[[121.4725,31.2255],[121.4680,31.2248],[121.4632,31.2238]]'),
('DB-R07', 'DB-A07', 'DB-A08', '淮海中路西段', 1120, 54.0, 60.0, 35.0, 3, 'two-way', '[[121.4632,31.2238],[121.4582,31.2232],[121.4530,31.2225]]'),
('DB-R10', 'DB-A09', 'DB-A10', '建国东路', 1440, 46.0, 105.0, 56.0, 3, 'two-way', '[[121.4778,31.2208],[121.4754,31.2200],[121.4730,31.2190]]'),
('DB-R11', 'DB-A10', 'DB-A11', '建国中路', 1650, 44.0, 110.0, 58.0, 3, 'two-way', '[[121.4730,31.2190],[121.4685,31.2175],[121.4640,31.2160]]'),
('DB-R12', 'DB-A11', 'DB-A12', '建国西路', 1360, 49.0, 88.0, 50.0, 3, 'two-way', '[[121.4640,31.2160],[121.4590,31.2153],[121.4538,31.2145]]'),
('DB-R15', 'DB-A01', 'DB-A05', '西藏中路', 1750, 40.0, 160.0, 74.0, 3, 'two-way', '[[121.4756,31.2356],[121.4762,31.2316],[121.4770,31.2275]]'),
('DB-R16', 'DB-A05', 'DB-A09', '西藏南路', 1900, 36.0, 175.0, 76.0, 3, 'two-way', '[[121.4770,31.2275],[121.4774,31.2242],[121.4778,31.2208]]'),
('DB-R18', 'DB-A02', 'DB-A06', '黄陂北路', 1980, 42.0, 145.0, 70.0, 3, 'two-way', '[[121.4710,31.2335],[121.4718,31.2295],[121.4725,31.2255]]'),
('DB-R19', 'DB-A06', 'DB-A10', '黄陂南路', 1720, 41.0, 130.0, 66.0, 3, 'two-way', '[[121.4725,31.2255],[121.4728,31.2223],[121.4730,31.2190]]'),
('DB-R21', 'DB-A03', 'DB-A07', '瑞金二路北段', 2100, 35.0, 200.0, 85.0, 3, 'two-way', '[[121.4612,31.2318],[121.4622,31.2278],[121.4632,31.2238]]'),
('DB-R22', 'DB-A07', 'DB-A11', '瑞金二路南段', 980, 56.0, 42.0, 30.0, 3, 'two-way', '[[121.4632,31.2238],[121.4636,31.2200],[121.4640,31.2160]]'),
('DB-R24', 'DB-A04', 'DB-A08', '常熟路', 860, 60.0, 35.0, 20.0, 3, 'two-way', '[[121.4518,31.2305],[121.4524,31.2265],[121.4530,31.2225]]'),
('DB-R25', 'DB-A08', 'DB-A12', '襄阳南路', 720, 62.0, 30.0, 18.0, 3, 'two-way', '[[121.4530,31.2225],[121.4534,31.2185],[121.4538,31.2145]]');

insert into dashboard_vehicle (id, road_id, progress, speed, vehicle_type, lane_index) values
('DB-V001', 'DB-R02', 0.32, 35.0, 'normal', 0),
('DB-V002', 'DB-R02', 0.58, 40.0, 'normal', 1),
('DB-V003', 'DB-R06', 0.15, 28.0, 'normal', 2),
('DB-V004', 'DB-R06', 0.45, 32.0, 'normal', 0),
('DB-V005', 'DB-R06', 0.72, 38.0, 'normal', 1),
('DB-V006', 'DB-R21', 0.22, 25.0, 'normal', 0),
('DB-V007', 'DB-R21', 0.48, 30.0, 'normal', 1),
('DB-V008', 'DB-R22', 0.76, 28.0, 'normal', 2),
('DB-V009', 'DB-R19', 0.60, 42.0, 'normal', 0),
('DB-V010', 'DB-R01', 0.85, 48.0, 'normal', 1),
('DB-V011', 'DB-R12', 0.30, 34.0, 'normal', 0),
('DB-V012', 'DB-R15', 0.55, 40.0, 'normal', 2),
('DB-E001', 'DB-R01', 0.40, 62.0, 'ambulance', 0),
('DB-E002', 'DB-R11', 0.18, 55.0, 'firetruck', 1),
('DB-V013', 'DB-R03', 0.90, 50.0, 'normal', 0),
('DB-V014', 'DB-R16', 0.25, 38.0, 'normal', 1),
('DB-V015', 'DB-R10', 0.55, 30.0, 'normal', 2);

insert into dashboard_emergency_vehicle (
    id, vehicle_type, current_intersection_id, destination, green_wave_active, eta
) values
('DB-E001', 'ambulance', 'DB-A01', '上海市第一人民医院方向', true, 8);

insert into dashboard_emergency_route (sequence_no, intersection_id) values
(1, 'DB-A01'),
(2, 'DB-A02'),
(3, 'DB-A06'),
(4, 'DB-A10');

insert into dashboard_alert (
    id, type, level, title, location, event_time, intersection_id, acknowledged
) values
('DB-ALT001', 'device_fault', 'error', 'A06 黄陂南路-淮海中路信号控制器故障', '黄陂南路-淮海中路', '2026-07-09 09:12:18', 'DB-A06', false),
('DB-ALT002', 'abnormal_congestion', 'error', '淮海中路中段拥堵指数超过阈值', 'DB-R06 淮海中路中段', '2026-07-09 09:10:05', 'DB-A06', false),
('DB-ALT003', 'control_failure', 'error', 'A06 绿波同步丢失，周边路口降级运行', '淮海中路沿线', '2026-07-09 09:06:45', 'DB-A06', false),
('DB-ALT004', 'abnormal_congestion', 'warning', '瑞金二路北段车流量超过阈值', 'DB-R21 瑞金二路北段', '2026-07-09 08:58:42', 'DB-A03', false),
('DB-ALT005', 'emergency_vehicle_enter', 'emergency', 'DB-E001 救护车申请应急绿波通道', '南京东路至建国东路方向', '2026-07-09 09:14:09', 'DB-A01', false),
('DB-ALT006', 'device_offline', 'warning', 'DB-A12 襄阳南路-建国西路信号机离线', '襄阳南路-建国西路', '2026-07-09 08:47:10', 'DB-A12', true);

insert into dashboard_statistics (
    id, total_flow, average_speed, average_wait_time, congestion_index, congested_road_count,
    optimized_intersection_count, emergency_vehicle_count, device_online_rate, today_alert_count,
    green_wave_count
) values
(1, 9372, 41.8, 35.6, 63.0, 5, 8, 2, 66.7, 6, 1);

insert into dashboard_compare_metric (
    metric_key, name, traditional_value, ai_value, unit, direction
) values
('averageWaitTime', '平均等待时间', 46.8, 28.6, 's', 'lower'),
('averageSpeed', '平均通行速度', 38.2, 45.8, 'km/h', 'higher'),
('queueLength', '路口排队长度', 185.0, 112.0, 'm', 'lower'),
('emergencyPassTime', '应急车辆通行时间', 14.5, 5.2, 'min', 'lower');

insert into dashboard_congestion_trend (sequence_no, time_label, metric_value) values
(1, '08:15', 45.0),
(2, '08:20', 48.0),
(3, '08:25', 51.0),
(4, '08:30', 55.0),
(5, '08:35', 61.0),
(6, '08:40', 67.0),
(7, '08:45', 72.0),
(8, '08:50', 76.0),
(9, '08:55', 69.0),
(10, '09:00', 63.0),
(11, '09:05', 58.0),
(12, '09:10', 62.0),
(13, '09:15', 58.0);

insert into dashboard_assistant_reply (keyword, reply) values
('拥堵', '当前路网共有 5 处拥堵路段。建议优先优化 DB-A06 黄陂南路-淮海中路，拥堵指数已达 92；同时对 DB-R06 淮海中路中段启用分流引导。'),
('绿波', '当前有 1 条应急绿波通道处于激活状态，路线为 DB-A01-DB-A02-DB-A06-DB-A10。建议维持东西向连续放行，并监控 DB-A06 的排队消散速度。'),
('应急', '检测到救护车 DB-E001 已进入南京东路方向，预计 8 分钟到达目标区域。建议提前激活 DB-A02 至 DB-A10 沿线相位优先。'),
('信号', '8 个路口已启用 AI 自适应优化，DB-A06 与 DB-A11 信号控制器故障并降级运行，DB-A04 与 DB-A12 离线，需要安排巡检。'),
('设备', '当前 12 个信号路口中 8 个在线、2 个故障、2 个离线，设备在线率 66.7%。优先处理 DB-A06、DB-A11、DB-A04 与 DB-A12。');
