alter table analytics_monitoring_record
    add column control_strategy varchar(32) not null default 'FixedTime';

delete from analytics_toast;
delete from analytics_monitoring_record;
delete from analytics_scatter_point;
delete from analytics_composition_item;
delete from analytics_heatmap_cell;
delete from analytics_building_summary;
delete from analytics_hourly_point;
delete from analytics_daily_point;
delete from analytics_status_bucket;
delete from analytics_metric;
delete from analytics_overview;

insert into analytics_overview (id, sample_count, sample_rate, health_score, sampled_point_id) values
(1, 34752, 96, 91, 'intersection_3_4-48');

insert into analytics_metric (sequence_no, label, detail, tone, metric_value) values
(1, '今日累计通行量', '2026-07-10 00:00 起全路网累计通过车辆数，来自数据库 V4 交通数据集。', 'sky', '87,645 辆'),
(2, '当前平均排队长度', '当前 12 个路口进口道平均排队长度，已按数据库样本刷新。', 'emerald', '6.4 辆'),
(3, '当前平均等待时间', '当前全路网车辆平均等待时间，数据库样本显示较上一版下降。', 'amber', '39 秒'),
(4, '自适应控制覆盖率', '接入 AI 自适应控制策略的路口占比，来自控制策略统计表。', 'sky', '91.7%'),
(5, '今日拥堵/事件告警', '今日已触发的拥堵与应急事件告警数，数据库已改为新值。', 'rose', '3 条');

insert into analytics_status_bucket (sequence_no, label, tone, bucket_count) values
(1, '畅通', 'emerald', 8),
(2, '缓行', 'amber', 2),
(3, '拥堵', 'rose', 1),
(4, '离线', 'slate', 1);

insert into analytics_daily_point (sequence_no, date_label, electricity, hvac, occupancy, water) values
(1, '06-29', 51240, 0, 30.4, 0),
(2, '06-30', 54810, 0, 34.1, 0),
(3, '07-01', 57360, 0, 37.8, 0),
(4, '07-02', 60290, 0, 41.6, 0),
(5, '07-03', 61720, 0, 44.2, 0),
(6, '07-04', 58640, 0, 39.4, 0),
(7, '07-05', 63180, 0, 45.1, 0),
(8, '07-06', 68890, 0, 52.5, 0),
(9, '07-07', 72430, 0, 57.9, 0),
(10, '07-08', 76820, 0, 62.7, 0),
(11, '07-09', 81260, 0, 66.8, 0),
(12, '07-10', 87645, 0, 71.3, 0);

insert into analytics_hourly_point (sequence_no, hour_label, electricity, hvac, occupancy, temperature) values
(1, '00:00', 520, 0, 42, 4.6),
(2, '06:00', 1460, 0, 78, 12.4),
(3, '12:00', 1820, 0, 84, 16.8),
(4, '18:00', 2480, 0, 112, 24.6);

insert into analytics_building_summary (
    sequence_no, building_id, building_type, average_occupancy, efficiency_score,
    electricity, hvac, status_label, warning_count, water
) values
(1, 'intersection_3_4', 'arterial', 71.3, 62, 94, 28, '高流量压控', 26, 2),
(2, 'intersection_2_4', 'secondary', 56.8, 76, 82, 19, '绿波协调', 15, 1),
(3, 'intersection_3_2', 'arterial', 49.6, 84, 74, 14, '排队消散', 9, 0),
(4, 'intersection_1_3', 'branch', 33.2, 93, 58, 7, '运行平稳', 4, 0);

insert into analytics_heatmap_cell (sequence_no, date_label, hour_label, electricity, intensity, occupancy) values
(1, '07-04', '00:00', 430, 0.17, 38),
(2, '07-04', '06:00', 1080, 0.43, 70),
(3, '07-04', '12:00', 1380, 0.55, 82),
(4, '07-04', '18:00', 1560, 0.62, 88),
(5, '07-05', '00:00', 470, 0.19, 39),
(6, '07-05', '06:00', 1160, 0.46, 72),
(7, '07-05', '12:00', 1480, 0.59, 84),
(8, '07-05', '18:00', 1690, 0.68, 91),
(9, '07-06', '00:00', 510, 0.20, 40),
(10, '07-06', '06:00', 1260, 0.50, 75),
(11, '07-06', '12:00', 1620, 0.65, 89),
(12, '07-06', '18:00', 1880, 0.75, 98),
(13, '07-07', '00:00', 550, 0.22, 41),
(14, '07-07', '06:00', 1370, 0.55, 79),
(15, '07-07', '12:00', 1740, 0.70, 93),
(16, '07-07', '18:00', 2030, 0.81, 104),
(17, '07-08', '00:00', 590, 0.24, 42),
(18, '07-08', '06:00', 1460, 0.58, 81),
(19, '07-08', '12:00', 1880, 0.75, 96),
(20, '07-08', '18:00', 2180, 0.87, 108),
(21, '07-09', '00:00', 640, 0.26, 43),
(22, '07-09', '06:00', 1540, 0.62, 83),
(23, '07-09', '12:00', 1980, 0.79, 98),
(24, '07-09', '18:00', 2310, 0.92, 110),
(25, '07-10', '00:00', 690, 0.28, 44),
(26, '07-10', '06:00', 1680, 0.67, 86),
(27, '07-10', '12:00', 2140, 0.86, 101),
(28, '07-10', '18:00', 2480, 0.99, 112);

insert into analytics_composition_item (sequence_no, label, color, item_value) values
(1, '东西直行', '#3b82f6', 29480),
(2, '南北直行', '#22c55e', 27315),
(3, '东西左转', '#f59e0b', 12840),
(4, '南北左转', '#ef4444', 11760),
(5, '应急优先', '#8b5cf6', 2100),
(6, '其他', '#06b6d4', 4150);

insert into analytics_scatter_point (
    sequence_no, point_id, building_id, hour_label, electricity, occupancy, temperature, tone
) values
(1, 'intersection_1_1-01', 'intersection_1_1', '00:00', 4.2, 260, 18.0, 'sky'),
(2, 'intersection_1_1-02', 'intersection_1_1', '06:00', 6.8, 520, 27.0, 'sky'),
(3, 'intersection_1_1-03', 'intersection_1_1', '12:00', 8.1, 720, 33.0, 'sky'),
(4, 'intersection_1_1-04', 'intersection_1_1', '18:00', 10.4, 880, 42.0, 'sky'),
(5, 'intersection_1_2-05', 'intersection_1_2', '00:00', 4.8, 310, 19.0, 'sky'),
(6, 'intersection_1_2-06', 'intersection_1_2', '06:00', 7.4, 590, 29.0, 'sky'),
(7, 'intersection_1_2-07', 'intersection_1_2', '12:00', 9.2, 790, 35.0, 'sky'),
(8, 'intersection_1_2-08', 'intersection_1_2', '18:00', 11.1, 940, 44.0, 'sky'),
(9, 'intersection_1_3-09', 'intersection_1_3', '00:00', 5.1, 340, 20.0, 'sky'),
(10, 'intersection_1_3-10', 'intersection_1_3', '06:00', 7.9, 630, 31.0, 'sky'),
(11, 'intersection_1_3-11', 'intersection_1_3', '12:00', 9.8, 840, 37.0, 'sky'),
(12, 'intersection_1_3-12', 'intersection_1_3', '18:00', 12.0, 990, 46.0, 'sky'),
(13, 'intersection_2_1-13', 'intersection_2_1', '00:00', 5.6, 390, 22.0, 'emerald'),
(14, 'intersection_2_1-14', 'intersection_2_1', '06:00', 8.6, 700, 34.0, 'emerald'),
(15, 'intersection_2_1-15', 'intersection_2_1', '12:00', 10.7, 920, 41.0, 'emerald'),
(16, 'intersection_2_1-16', 'intersection_2_1', '18:00', 13.6, 1120, 49.0, 'emerald'),
(17, 'intersection_2_2-17', 'intersection_2_2', '00:00', 6.2, 430, 23.0, 'emerald'),
(18, 'intersection_2_2-18', 'intersection_2_2', '06:00', 9.4, 760, 35.0, 'emerald'),
(19, 'intersection_2_2-19', 'intersection_2_2', '12:00', 11.8, 980, 43.0, 'emerald'),
(20, 'intersection_2_2-20', 'intersection_2_2', '18:00', 14.4, 1180, 52.0, 'emerald'),
(21, 'intersection_2_3-21', 'intersection_2_3', '00:00', 6.8, 470, 24.0, 'emerald'),
(22, 'intersection_2_3-22', 'intersection_2_3', '06:00', 10.1, 820, 37.0, 'emerald'),
(23, 'intersection_2_3-23', 'intersection_2_3', '12:00', 12.6, 1050, 45.0, 'emerald'),
(24, 'intersection_2_3-24', 'intersection_2_3', '18:00', 15.2, 1240, 54.0, 'emerald'),
(25, 'intersection_1_4-25', 'intersection_1_4', '00:00', 8.4, 560, 28.0, 'amber'),
(26, 'intersection_1_4-26', 'intersection_1_4', '06:00', 12.6, 910, 40.0, 'amber'),
(27, 'intersection_1_4-27', 'intersection_1_4', '12:00', 15.2, 1160, 48.0, 'amber'),
(28, 'intersection_1_4-28', 'intersection_1_4', '18:00', 18.7, 1380, 60.0, 'amber'),
(29, 'intersection_2_4-29', 'intersection_2_4', '00:00', 9.1, 610, 30.0, 'amber'),
(30, 'intersection_2_4-30', 'intersection_2_4', '06:00', 13.8, 980, 43.0, 'amber'),
(31, 'intersection_2_4-31', 'intersection_2_4', '12:00', 16.6, 1250, 51.0, 'amber'),
(32, 'intersection_2_4-32', 'intersection_2_4', '18:00', 20.4, 1480, 64.0, 'amber'),
(33, 'intersection_3_1-33', 'intersection_3_1', '00:00', 8.8, 590, 29.0, 'amber'),
(34, 'intersection_3_1-34', 'intersection_3_1', '06:00', 13.2, 940, 42.0, 'amber'),
(35, 'intersection_3_1-35', 'intersection_3_1', '12:00', 15.9, 1210, 50.0, 'amber'),
(36, 'intersection_3_1-36', 'intersection_3_1', '18:00', 19.6, 1430, 62.0, 'amber'),
(37, 'intersection_3_2-37', 'intersection_3_2', '00:00', 11.2, 700, 34.0, 'rose'),
(38, 'intersection_3_2-38', 'intersection_3_2', '06:00', 16.8, 1100, 49.0, 'rose'),
(39, 'intersection_3_2-39', 'intersection_3_2', '12:00', 20.6, 1380, 60.0, 'rose'),
(40, 'intersection_3_2-40', 'intersection_3_2', '18:00', 25.1, 1630, 74.0, 'rose'),
(41, 'intersection_3_3-41', 'intersection_3_3', '00:00', 12.4, 760, 36.0, 'rose'),
(42, 'intersection_3_3-42', 'intersection_3_3', '06:00', 18.2, 1180, 52.0, 'rose'),
(43, 'intersection_3_3-43', 'intersection_3_3', '12:00', 22.4, 1490, 64.0, 'rose'),
(44, 'intersection_3_3-44', 'intersection_3_3', '18:00', 27.6, 1740, 79.0, 'rose'),
(45, 'intersection_3_4-45', 'intersection_3_4', '00:00', 13.6, 820, 39.0, 'rose'),
(46, 'intersection_3_4-46', 'intersection_3_4', '06:00', 19.8, 1280, 56.0, 'rose'),
(47, 'intersection_3_4-47', 'intersection_3_4', '12:00', 24.7, 1580, 68.0, 'rose'),
(48, 'intersection_3_4-48', 'intersection_3_4', '18:00', 30.2, 1820, 86.0, 'rose');

insert into analytics_monitoring_record (
    sequence_no, record_id, building_id, building_type, chilled_water_return_temp,
    chilled_water_supply_temp, device_id, device_status, control_strategy, electricity_kwh,
    env_humidity, env_temperature, hvac_kwh, monitor_time, occupancy_density, water_m3
) values
(1, 3042, '路口 3-4', 'intersection_3_4', 27.8, 112, '东西直行', 'warning', 'Traffic-R1', 1480, 112, 14.8, 27.8, '2026-07-10 18:00', 64.9, 68.4),
(2, 3041, '路口 2-4', 'intersection_2_4', 18.6, 96, '南北直行', 'maintenance', 'MaxPressure', 1320, 96, 22.4, 18.6, '2026-07-10 18:00', 55.7, 49.2),
(3, 3040, '路口 3-2', 'intersection_3_2', 14.2, 88, '东西左转', 'normal', 'Traffic-R1', 1180, 88, 31.6, 14.2, '2026-07-10 18:00', 51.0, 36.8),
(4, 3039, '路口 1-3', 'intersection_1_3', 7.4, 58, '南北左转', 'normal', 'RL', 760, 58, 46.5, 7.4, '2026-07-10 18:00', 33.6, 22.1),
(5, 3038, '路口 3-4', 'intersection_3_4', 22.6, 104, '东西直行', 'warning', 'Traffic-R1', 1390, 104, 17.2, 22.6, '2026-07-10 12:00', 60.3, 58.7),
(6, 3037, '路口 2-4', 'intersection_2_4', 15.8, 90, '南北直行', 'maintenance', 'MaxPressure', 1205, 90, 25.8, 15.8, '2026-07-10 12:00', 52.2, 42.6),
(7, 3036, '路口 2-2', 'intersection_2_2', 10.4, 74, '东西左转', 'normal', 'RL', 980, 74, 37.4, 10.4, '2026-07-10 12:00', 42.9, 30.4),
(8, 3035, '路口 1-1', 'intersection_1_1', 5.6, 46, '南北左转', 'normal', 'FixedTime', 620, 46, 52.1, 5.6, '2026-07-10 12:00', 26.7, 16.8),
(9, 3034, '路口 3-3', 'intersection_3_3', 19.4, 98, '东西直行', 'warning', 'Traffic-R1', 1260, 98, 19.8, 19.4, '2026-07-10 06:00', 56.8, 52.5),
(10, 3033, '路口 1-4', 'intersection_1_4', 11.6, 78, '南北直行', 'maintenance', 'MaxPressure', 1040, 78, 34.6, 11.6, '2026-07-10 06:00', 45.2, 33.9),
(11, 3032, '路口 2-1', 'intersection_2_1', 6.8, 54, '东西左转', 'normal', 'RL', 720, 54, 48.9, 6.8, '2026-07-10 06:00', 31.3, 20.7),
(12, 3031, '路口 1-2', 'intersection_1_2', 4.2, 40, '南北左转', 'offline', 'FixedTime', 480, 40, 0, 4.2, '2026-07-10 06:00', 23.2, 0);

insert into analytics_toast (sequence_no, toast_id, title, body, tone) values
(1, 9101, '数据库数据集已刷新', '今日累计通行量已由数据库改为 87,645 辆，页面刷新即可看到新值。', 'emerald'),
(2, 9102, '交通指标已接入', '小时流量、通行构成、热力矩阵和近期明细均来自 V4 交通分析数据集。', 'cyan'),
(3, 9103, '拥堵事件告警', '路口 3-4 晚高峰排队长度偏高，控制策略已切换为 Traffic-R1。', 'rose');
