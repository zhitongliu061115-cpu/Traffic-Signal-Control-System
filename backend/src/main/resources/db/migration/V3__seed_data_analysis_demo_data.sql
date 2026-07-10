create table analytics_overview (
    id integer primary key,
    sample_count integer not null,
    sample_rate integer not null,
    health_score integer not null,
    sampled_point_id varchar(64) not null
);

create table analytics_metric (
    sequence_no integer primary key,
    label varchar(64) not null,
    detail varchar(256) not null,
    tone varchar(16) not null,
    metric_value varchar(32) not null
);

create table analytics_status_bucket (
    sequence_no integer primary key,
    label varchar(32) not null,
    tone varchar(16) not null,
    bucket_count integer not null
);

create table analytics_daily_point (
    sequence_no integer primary key,
    date_label varchar(16) not null,
    electricity double precision not null,
    hvac double precision not null,
    occupancy double precision not null,
    water double precision not null
);

create table analytics_hourly_point (
    sequence_no integer primary key,
    hour_label varchar(16) not null,
    electricity double precision not null,
    hvac double precision not null,
    occupancy double precision not null,
    temperature double precision not null
);

create table analytics_building_summary (
    sequence_no integer primary key,
    building_id varchar(32) not null,
    building_type varchar(32) not null,
    average_occupancy double precision not null,
    efficiency_score integer not null,
    electricity double precision not null,
    hvac double precision not null,
    status_label varchar(32) not null,
    warning_count integer not null,
    water double precision not null
);

create table analytics_heatmap_cell (
    sequence_no integer primary key,
    date_label varchar(16) not null,
    hour_label varchar(16) not null,
    electricity double precision not null,
    intensity double precision not null,
    occupancy double precision not null
);

create table analytics_composition_item (
    sequence_no integer primary key,
    label varchar(64) not null,
    color varchar(16) not null,
    item_value double precision not null
);

create table analytics_scatter_point (
    sequence_no integer primary key,
    point_id varchar(64) not null,
    building_id varchar(32) not null,
    hour_label varchar(16) not null,
    electricity double precision not null,
    occupancy double precision not null,
    temperature double precision not null,
    tone varchar(16) not null
);

create table analytics_monitoring_record (
    sequence_no integer primary key,
    record_id bigint not null,
    building_id varchar(32) not null,
    building_type varchar(32) not null,
    chilled_water_return_temp double precision not null,
    chilled_water_supply_temp double precision not null,
    device_id varchar(64) not null,
    device_status varchar(32) not null,
    electricity_kwh double precision not null,
    env_humidity double precision not null,
    env_temperature double precision not null,
    hvac_kwh double precision not null,
    monitor_time varchar(32) not null,
    occupancy_density double precision not null,
    water_m3 double precision not null
);

create table analytics_toast (
    sequence_no integer primary key,
    toast_id bigint not null,
    title varchar(64) not null,
    body varchar(256) not null,
    tone varchar(16) not null
);

insert into analytics_overview (id, sample_count, sample_rate, health_score, sampled_point_id) values
(1, 1366, 35, 89, 'BLDG-C-07-27');

insert into analytics_metric (sequence_no, label, detail, tone, metric_value) values
(1, '今日累计', '2026-07-10 00:00 起累计电耗，来自数据库采样汇总。', 'sky', '6852 kWh'),
(2, '当前人流指数', '当前时刻楼宇人流活跃度估计值，来自最近监测记录。', 'emerald', '73.8'),
(3, '今日峰值负荷', 'BLDG-C-07 · 18:00 负荷峰值。', 'amber', '196.4 kWh'),
(4, 'HVAC 当前占比', '当前时段暖通系统电耗占比。', 'sky', '44.8%'),
(5, '今日预警', '最新监测时间 2026-07-10 18:00。', 'rose', '5 条');

insert into analytics_status_bucket (sequence_no, label, tone, bucket_count) values
(1, '正常', 'emerald', 168),
(2, '预警', 'rose', 11),
(3, '维护中', 'amber', 4),
(4, '离线', 'slate', 2);

insert into analytics_daily_point (sequence_no, date_label, electricity, hvac, occupancy, water) values
(1, '06-29', 501.8, 210.9, 53.2, 67.1),
(2, '06-30', 486.6, 204.4, 51.5, 64.5),
(3, '07-01', 522.5, 229.2, 58.1, 69.7),
(4, '07-02', 553.4, 238.4, 62.8, 72.4),
(5, '07-03', 534.1, 226.7, 59.4, 70.2),
(6, '07-04', 497.6, 205.1, 50.2, 63.8),
(7, '07-05', 518.9, 218.5, 56.7, 66.9),
(8, '07-06', 575.2, 251.9, 65.6, 75.3),
(9, '07-07', 604.6, 267.4, 69.4, 77.1),
(10, '07-08', 624.3, 276.2, 72.8, 79.8),
(11, '07-09', 642.8, 280.1, 71.4, 81.5),
(12, '07-10', 685.2, 306.9, 73.8, 84.2);

insert into analytics_hourly_point (sequence_no, hour_label, electricity, hvac, occupancy, temperature) values
(1, '00:00', 124.5, 47.2, 24.1, 24.8),
(2, '06:00', 158.3, 65.4, 51.6, 25.4),
(3, '12:00', 182.1, 83.7, 70.2, 26.8),
(4, '18:00', 196.4, 92.5, 73.8, 27.6);

insert into analytics_building_summary (
    sequence_no, building_id, building_type, average_occupancy, efficiency_score,
    electricity, hvac, status_label, warning_count, water
) values
(1, 'BLDG-C-07', 'lab', 74.6, 69, 1786.5, 806.4, '预警优先', 5, 203.7),
(2, 'BLDG-A-03', 'office', 68.1, 88, 1608.9, 648.3, '运行稳定', 1, 174.8),
(3, 'BLDG-D-02', 'mixed-use', 61.5, 80, 1519.4, 671.2, '维护观察', 3, 158.4),
(4, 'BLDG-B-01', 'teaching', 58.7, 91, 1328.2, 514.7, '运行稳定', 0, 139.6);

insert into analytics_heatmap_cell (sequence_no, date_label, hour_label, electricity, intensity, occupancy) values
(1, '07-04', '00:00', 82.1, 0.38, 22.7),
(2, '07-04', '06:00', 112.5, 0.52, 43.8),
(3, '07-04', '12:00', 142.6, 0.66, 61.5),
(4, '07-04', '18:00', 151.3, 0.70, 63.4),
(5, '07-05', '00:00', 86.4, 0.40, 24.9),
(6, '07-05', '06:00', 118.1, 0.55, 47.2),
(7, '07-05', '12:00', 150.2, 0.69, 66.4),
(8, '07-05', '18:00', 160.9, 0.74, 68.9),
(9, '07-06', '00:00', 94.2, 0.43, 28.5),
(10, '07-06', '06:00', 132.8, 0.61, 53.1),
(11, '07-06', '12:00', 166.7, 0.77, 70.6),
(12, '07-06', '18:00', 178.2, 0.82, 72.8),
(13, '07-07', '00:00', 98.8, 0.46, 29.8),
(14, '07-07', '06:00', 138.4, 0.64, 56.6),
(15, '07-07', '12:00', 176.5, 0.81, 73.7),
(16, '07-07', '18:00', 186.9, 0.86, 75.6),
(17, '07-08', '00:00', 102.7, 0.47, 30.5),
(18, '07-08', '06:00', 143.2, 0.66, 58.9),
(19, '07-08', '12:00', 182.4, 0.84, 75.4),
(20, '07-08', '18:00', 190.1, 0.88, 76.2),
(21, '07-09', '00:00', 106.5, 0.49, 31.2),
(22, '07-09', '06:00', 148.8, 0.69, 60.4),
(23, '07-09', '12:00', 186.7, 0.86, 74.6),
(24, '07-09', '18:00', 193.5, 0.89, 73.5),
(25, '07-10', '00:00', 111.9, 0.52, 33.8),
(26, '07-10', '06:00', 154.6, 0.71, 62.7),
(27, '07-10', '12:00', 189.8, 0.88, 72.5),
(28, '07-10', '18:00', 196.4, 0.91, 73.8);

insert into analytics_composition_item (sequence_no, label, color, item_value) values
(1, '暖通系统', '#3b82f6', 3069.0),
(2, '照明系统', '#22c55e', 1234.6),
(3, '插座与设备', '#f59e0b', 986.5),
(4, '公共区域', '#ef4444', 685.2),
(5, '实验与专用负荷', '#8b5cf6', 516.8),
(6, '其他损耗', '#06b6d4', 360.1);

insert into analytics_scatter_point (
    sequence_no, point_id, building_id, hour_label, electricity, occupancy, temperature, tone
) values
(1, 'BLDG-A-03-01', 'BLDG-A-03', '00:00', 122.0, 48.0, 24.6, 'sky'),
(2, 'BLDG-A-03-02', 'BLDG-A-03', '06:00', 145.7, 60.0, 25.3, 'sky'),
(3, 'BLDG-A-03-03', 'BLDG-A-03', '12:00', 167.4, 70.0, 26.1, 'sky'),
(4, 'BLDG-A-03-04', 'BLDG-A-03', '18:00', 179.1, 74.0, 26.7, 'sky'),
(5, 'BLDG-A-05-05', 'BLDG-A-05', '00:00', 132.0, 51.4, 24.9, 'sky'),
(6, 'BLDG-A-05-06', 'BLDG-A-05', '06:00', 155.7, 63.4, 25.6, 'sky'),
(7, 'BLDG-A-05-07', 'BLDG-A-05', '12:00', 177.4, 73.4, 26.4, 'sky'),
(8, 'BLDG-A-05-08', 'BLDG-A-05', '18:00', 189.1, 77.4, 27.0, 'sky'),
(9, 'BLDG-A-08-09', 'BLDG-A-08', '00:00', 122.0, 46.8, 24.4, 'sky'),
(10, 'BLDG-A-08-10', 'BLDG-A-08', '06:00', 145.7, 58.8, 25.1, 'sky'),
(11, 'BLDG-A-08-11', 'BLDG-A-08', '12:00', 167.4, 68.8, 25.9, 'sky'),
(12, 'BLDG-A-08-12', 'BLDG-A-08', '18:00', 179.1, 72.8, 26.5, 'sky'),
(13, 'BLDG-B-01-13', 'BLDG-B-01', '00:00', 110.0, 46.2, 24.3, 'emerald'),
(14, 'BLDG-B-01-14', 'BLDG-B-01', '06:00', 133.7, 58.2, 25.0, 'emerald'),
(15, 'BLDG-B-01-15', 'BLDG-B-01', '12:00', 155.4, 68.2, 25.8, 'emerald'),
(16, 'BLDG-B-01-16', 'BLDG-B-01', '18:00', 167.1, 72.2, 26.4, 'emerald'),
(17, 'BLDG-B-04-17', 'BLDG-B-04', '00:00', 121.0, 45.0, 24.5, 'emerald'),
(18, 'BLDG-B-04-18', 'BLDG-B-04', '06:00', 144.7, 57.0, 25.2, 'emerald'),
(19, 'BLDG-B-04-19', 'BLDG-B-04', '12:00', 166.4, 67.0, 26.0, 'emerald'),
(20, 'BLDG-B-04-20', 'BLDG-B-04', '18:00', 178.1, 71.0, 26.6, 'emerald'),
(21, 'BLDG-B-09-21', 'BLDG-B-09', '00:00', 112.0, 41.8, 24.0, 'emerald'),
(22, 'BLDG-B-09-22', 'BLDG-B-09', '06:00', 135.7, 53.8, 24.7, 'emerald'),
(23, 'BLDG-B-09-23', 'BLDG-B-09', '12:00', 157.4, 63.8, 25.5, 'emerald'),
(24, 'BLDG-B-09-24', 'BLDG-B-09', '18:00', 169.1, 67.8, 26.1, 'emerald'),
(25, 'BLDG-C-07-25', 'BLDG-C-07', '00:00', 153.0, 56.0, 23.9, 'amber'),
(26, 'BLDG-C-07-26', 'BLDG-C-07', '06:00', 176.7, 68.0, 24.6, 'amber'),
(27, 'BLDG-C-07-27', 'BLDG-C-07', '12:00', 198.4, 78.0, 25.4, 'amber'),
(28, 'BLDG-C-07-28', 'BLDG-C-07', '18:00', 210.1, 82.0, 26.0, 'amber'),
(29, 'BLDG-C-11-29', 'BLDG-C-11', '00:00', 164.0, 59.4, 24.4, 'amber'),
(30, 'BLDG-C-11-30', 'BLDG-C-11', '06:00', 187.7, 71.4, 25.1, 'amber'),
(31, 'BLDG-C-11-31', 'BLDG-C-11', '12:00', 209.4, 81.4, 25.9, 'amber'),
(32, 'BLDG-C-11-32', 'BLDG-C-11', '18:00', 221.1, 85.4, 26.5, 'amber'),
(33, 'BLDG-C-15-33', 'BLDG-C-15', '00:00', 154.0, 54.8, 24.2, 'amber'),
(34, 'BLDG-C-15-34', 'BLDG-C-15', '06:00', 177.7, 66.8, 24.9, 'amber'),
(35, 'BLDG-C-15-35', 'BLDG-C-15', '12:00', 199.4, 76.8, 25.7, 'amber'),
(36, 'BLDG-C-15-36', 'BLDG-C-15', '18:00', 211.1, 80.8, 26.3, 'amber'),
(37, 'BLDG-D-02-37', 'BLDG-D-02', '00:00', 144.0, 53.4, 25.1, 'rose'),
(38, 'BLDG-D-02-38', 'BLDG-D-02', '06:00', 167.7, 65.4, 25.8, 'rose'),
(39, 'BLDG-D-02-39', 'BLDG-D-02', '12:00', 189.4, 75.4, 26.6, 'rose'),
(40, 'BLDG-D-02-40', 'BLDG-D-02', '18:00', 201.1, 79.4, 27.2, 'rose'),
(41, 'BLDG-D-06-41', 'BLDG-D-06', '00:00', 159.0, 58.4, 25.5, 'rose'),
(42, 'BLDG-D-06-42', 'BLDG-D-06', '06:00', 182.7, 70.4, 26.2, 'rose'),
(43, 'BLDG-D-06-43', 'BLDG-D-06', '12:00', 204.4, 80.4, 27.0, 'rose'),
(44, 'BLDG-D-06-44', 'BLDG-D-06', '18:00', 216.1, 84.4, 27.6, 'rose'),
(45, 'BLDG-D-09-45', 'BLDG-D-09', '00:00', 153.0, 56.8, 25.5, 'rose'),
(46, 'BLDG-D-09-46', 'BLDG-D-09', '06:00', 176.7, 68.8, 26.2, 'rose'),
(47, 'BLDG-D-09-47', 'BLDG-D-09', '12:00', 198.4, 78.8, 27.0, 'rose'),
(48, 'BLDG-D-09-48', 'BLDG-D-09', '18:00', 210.1, 82.8, 27.6, 'rose');

insert into analytics_monitoring_record (
    sequence_no, record_id, building_id, building_type, chilled_water_return_temp,
    chilled_water_supply_temp, device_id, device_status, electricity_kwh,
    env_humidity, env_temperature, hvac_kwh, monitor_time, occupancy_density, water_m3
) values
(1, 2042, 'BLDG-C-07', 'lab', 12.6, 7.6, 'BLDG-C-07-DEV-18', 'warning', 196.4, 47, 27.6, 92.5, '2026-07-10 18:00', 78.0, 20.6),
(2, 2041, 'BLDG-A-03', 'office', 11.9, 7.1, 'BLDG-A-03-DEV-21', 'normal', 178.2, 49, 27.3, 76.2, '2026-07-10 18:00', 74.5, 18.4),
(3, 2040, 'BLDG-D-02', 'mixed-use', 12.3, 7.2, 'BLDG-D-02-DEV-09', 'maintenance', 181.0, 52, 27.2, 79.1, '2026-07-10 18:00', 70.8, 16.9),
(4, 2039, 'BLDG-B-01', 'teaching', 11.3, 6.8, 'BLDG-B-01-DEV-14', 'normal', 158.8, 54, 26.7, 61.4, '2026-07-10 18:00', 65.0, 14.8),
(5, 2038, 'BLDG-C-07', 'lab', 12.2, 7.5, 'BLDG-C-07-DEV-11', 'warning', 188.7, 46, 27.1, 86.6, '2026-07-10 12:00', 76.4, 19.8),
(6, 2037, 'BLDG-A-03', 'office', 11.7, 7.0, 'BLDG-A-03-DEV-08', 'normal', 169.3, 48, 26.4, 72.1, '2026-07-10 12:00', 72.0, 17.2),
(7, 2036, 'BLDG-D-02', 'mixed-use', 12.4, 7.3, 'BLDG-D-02-DEV-16', 'warning', 176.9, 53, 26.8, 80.7, '2026-07-10 12:00', 69.6, 16.2),
(8, 2035, 'BLDG-B-01', 'teaching', 11.5, 6.9, 'BLDG-B-01-DEV-06', 'normal', 151.6, 55, 25.9, 56.9, '2026-07-10 12:00', 63.7, 14.1),
(9, 2034, 'BLDG-C-07', 'lab', 12.7, 7.7, 'BLDG-C-07-DEV-07', 'warning', 176.2, 47, 26.1, 81.5, '2026-07-10 06:00', 65.4, 17.8),
(10, 2033, 'BLDG-A-03', 'office', 11.5, 6.8, 'BLDG-A-03-DEV-19', 'normal', 151.4, 50, 25.6, 64.2, '2026-07-10 06:00', 62.1, 15.9),
(11, 2032, 'BLDG-D-02', 'mixed-use', 12.2, 7.1, 'BLDG-D-02-DEV-23', 'maintenance', 160.5, 52, 26.0, 67.8, '2026-07-10 06:00', 60.2, 15.1),
(12, 2031, 'BLDG-B-01', 'teaching', 11.1, 6.6, 'BLDG-B-01-DEV-02', 'normal', 136.8, 56, 25.0, 53.4, '2026-07-10 06:00', 56.0, 13.1);

insert into analytics_toast (sequence_no, toast_id, title, body, tone) values
(1, 9001, '监测流已接入', '当前采样速率 35 条/分钟，已接入 1366 条样本。', 'emerald'),
(2, 9002, '暖通能耗异常', 'BLDG-C-07 近 24 小时负荷持续偏高。', 'rose');
