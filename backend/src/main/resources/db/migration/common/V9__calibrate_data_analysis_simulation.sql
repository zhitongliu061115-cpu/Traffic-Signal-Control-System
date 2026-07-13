update analytics_overview
set sample_count = 34752,
    sample_rate = 96,
    health_score = 93,
    sampled_point_id = 'intersection_2_4-32',
    scatter_correlation = 0.78
where id = 1;

update analytics_metric
set metric_value = case sequence_no
        when 1 then '1,180 辆'
        when 2 then '5.6 辆'
        when 3 then '26 秒'
        when 4 then '86.0%'
        else '2 条'
    end,
    detail = case sequence_no
        when 1 then '今日 00:00 起全路网累计通过车辆数，来自数据库顺序采样。'
        when 2 then '当前 12 个路口进口道平均排队长度，来自最新数据库事件。'
        when 3 then '当前全路网车辆平均等待时间，来自最新数据库事件。'
        when 4 then '当前接入自适应控制策略的路口占比。'
        else '今日数据库事件中已记录的拥堵与异常告警数。'
    end;

delete from analytics_metric_trend_point;
insert into analytics_metric_trend_point (metric_sequence_no, point_sequence_no, point_value) values
(1, 1, 890), (1, 2, 940), (1, 3, 985), (1, 4, 1030), (1, 5, 1085), (1, 6, 1135), (1, 7, 1180),
(2, 1, 4.8), (2, 2, 5.1), (2, 3, 5.4), (2, 4, 5.0), (2, 5, 5.8), (2, 6, 5.5), (2, 7, 5.6),
(3, 1, 23), (3, 2, 25), (3, 3, 27), (3, 4, 24), (3, 5, 29), (3, 6, 27), (3, 7, 26),
(4, 1, 84.2), (4, 2, 84.8), (4, 3, 85.1), (4, 4, 85.4), (4, 5, 85.7), (4, 6, 85.9), (4, 7, 86.0),
(5, 1, 1), (5, 2, 1), (5, 3, 1), (5, 4, 2), (5, 5, 2), (5, 6, 2), (5, 7, 2);

update analytics_daily_point
set date_label = case sequence_no
        when 1 then '07-01' when 2 then '07-02' when 3 then '07-03' when 4 then '07-04'
        when 5 then '07-05' when 6 then '07-06' when 7 then '07-07' when 8 then '07-08'
        when 9 then '07-09' when 10 then '07-10' when 11 then '07-11' else '07-12'
    end,
    electricity = case sequence_no
        when 1 then 960 when 2 then 1015 when 3 then 1080 when 4 then 990
        when 5 then 1125 when 6 then 1190 when 7 then 1260 when 8 then 1215
        when 9 then 1295 when 10 then 1370 when 11 then 1285 else 1320
    end,
    hvac = 0,
    occupancy = case sequence_no
        when 1 then 24.8 when 2 then 26.2 when 3 then 28.1 when 4 then 25.6
        when 5 then 29.4 when 6 then 31.8 when 7 then 34.6 when 8 then 32.9
        when 9 then 35.8 when 10 then 38.2 when 11 then 33.7 else 34.9
    end,
    water = 0;

update analytics_hourly_point
set electricity = case sequence_no when 1 then 160 when 2 then 410 when 3 then 590 else 720 end,
    hvac = 0,
    occupancy = case sequence_no when 1 then 28 when 2 then 54 when 3 then 69 else 81 end,
    temperature = case sequence_no when 1 then 2.4 when 2 then 5.2 when 3 then 7.1 else 8.8 end;

update analytics_building_summary
set average_occupancy = case sequence_no when 1 then 67 when 2 then 59 when 3 then 52 else 41 end,
    efficiency_score = case sequence_no when 1 then 76 when 2 then 82 when 3 then 88 else 93 end,
    electricity = case sequence_no when 1 then 515 when 2 then 460 when 3 then 390 else 305 end,
    hvac = case sequence_no when 1 then 9.8 when 2 then 8.1 when 3 then 6.7 else 4.9 end,
    status_label = case sequence_no when 1 then '晚高峰关注' when 2 then '轻度缓行' when 3 then '运行稳定' else '运行稳定' end,
    warning_count = case sequence_no when 1 then 11 when 2 then 9 when 3 then 7 else 5 end,
    water = case sequence_no when 1 then 2 when 2 then 1 when 3 then 1 else 0 end;

update analytics_heatmap_cell
set electricity = case mod(sequence_no - 1, 4)
        when 0 then 2.2 when 1 then 5.0 when 2 then 7.0 else 9.4
    end + floor((sequence_no - 1) / 4.0) * 0.35,
    occupancy = case mod(sequence_no - 1, 4)
        when 0 then 26 when 1 then 51 when 2 then 68 else 80
    end + floor((sequence_no - 1) / 4.0);

update analytics_heatmap_cell
set intensity = least(0.95, electricity / 13.0);

update analytics_composition_item
set item_value = case sequence_no
        when 1 then 440 when 2 then 390 when 3 then 185
        when 4 then 170 when 5 then 15 else 120
    end;

update analytics_scatter_point
set electricity = 2.8
        + floor((sequence_no - 1) / 12.0) * 2.1
        + mod(sequence_no - 1, 4) * 0.9,
    occupancy = 160
        + floor((sequence_no - 1) / 12.0) * 120
        + mod(sequence_no - 1, 4) * 90,
    temperature = 18
        + (2.8 + floor((sequence_no - 1) / 12.0) * 2.1 + mod(sequence_no - 1, 4) * 0.9) * 2.4;

update analytics_strategy_metric
set baseline_value = case sequence_no when 1 then 9.8 when 2 then 620 when 3 then 42 when 4 then 190 else 1100 end,
    max_pressure_value = case sequence_no when 1 then 7.2 when 2 then 470 when 3 then 34 when 4 then 172 else 1230 end,
    traffic_r1_value = case sequence_no when 1 then 5.8 when 2 then 390 when 3 then 28 when 4 then 160 else 1320 end;

update analytics_monitoring_record
set chilled_water_return_temp = 9.5 - (sequence_no - 1) * 0.45,
    chilled_water_supply_temp = 48 + (9.5 - (sequence_no - 1) * 0.45) * 3,
    electricity_kwh = 720 - (sequence_no - 1) * 45,
    env_humidity = 48 + (9.5 - (sequence_no - 1) * 0.45) * 3,
    env_temperature = 52 - (9.5 - (sequence_no - 1) * 0.45) * 2.4,
    hvac_kwh = 9.5 - (sequence_no - 1) * 0.45,
    occupancy_density = 48 + (9.5 - (sequence_no - 1) * 0.45) * 3,
    water_m3 = 22 + (9.5 - (sequence_no - 1) * 0.45) * 2.2,
    control_strategy = case when sequence_no <= 8 then 'Traffic-R1' else 'MaxPressure' end,
    device_status = case when sequence_no <= 2 then 'warning' when sequence_no <= 4 then 'maintenance' else 'normal' end;

update analytics_toast
set title = case sequence_no when 1 then '数据库监测已接入' when 2 then '晚高峰运行提示' else '策略运行稳定' end,
    body = case sequence_no
        when 1 then '数据分析页正在按数据库游标逐条读取路口采样。'
        when 2 then '路口 3-4 当前排队略高，请持续关注。'
        else 'Traffic-R1 已连续覆盖主要路口监测记录。'
    end,
    tone = case sequence_no when 2 then 'rose' when 3 then 'emerald' else 'cyan' end;

update analytics_live_update
set cumulative_traffic = 1180 + cast(floor((sequence_no - 1) / 12.0) as integer),
    average_queue = 5.6 + 1.2 * sin(sequence_no / 37.0) + 0.4 * sin(sequence_no / 11.0),
    average_wait = 26 + 6 * sin(sequence_no / 41.0) + 2 * sin(sequence_no / 13.0),
    adaptive_coverage = 86.0 + mod(sequence_no - 1, 40) / 10.0,
    alert_count = 2 + cast(floor((sequence_no - 1) / 2500.0) as integer),
    health_score = case
        when 5.6 + 1.2 * sin(sequence_no / 37.0) + 0.4 * sin(sequence_no / 11.0) >= 6.6 then 89
        else 93
    end,
    normal_count = case
        when mod(sequence_no, 997) = 0
            and 5.6 + 1.2 * sin(sequence_no / 37.0) + 0.4 * sin(sequence_no / 11.0) >= 6.6 then 6
        when mod(sequence_no, 997) = 0 then 7
        when 5.6 + 1.2 * sin(sequence_no / 37.0) + 0.4 * sin(sequence_no / 11.0) >= 6.6 then 7
        else 8
    end,
    slow_count = 3,
    congested_count = case
        when 5.6 + 1.2 * sin(sequence_no / 37.0) + 0.4 * sin(sequence_no / 11.0) >= 6.6 then 2
        else 1
    end,
    offline_count = case when mod(sequence_no, 997) = 0 then 1 else 0 end,
    hour_label = case mod(cast(floor((sequence_no - 1) / 900.0) as integer), 4)
        when 0 then '00:00' when 1 then '06:00' when 2 then '12:00' else '18:00'
    end,
    hourly_flow = case mod(cast(floor((sequence_no - 1) / 900.0) as integer), 4)
        when 0 then 160 when 1 then 410 when 2 then 590 else 720
    end,
    hourly_saturation = case mod(cast(floor((sequence_no - 1) / 900.0) as integer), 4)
        when 0 then 28 when 1 then 54 when 2 then 69 else 81
    end,
    hourly_queue = case mod(cast(floor((sequence_no - 1) / 900.0) as integer), 4)
        when 0 then 2.4 when 1 then 5.2 when 2 then 7.1 else 8.8
    end,
    east_west_straight = 440,
    north_south_straight = 390,
    east_west_left = 185,
    north_south_left = 170,
    emergency_priority = 15,
    other_duration = 120,
    inflow_count = 280 + 105 * sin(sequence_no / 31.0) + 45 * sin(sequence_no / 7.0),
    queue_length = 5.4 + 1.8 * sin(sequence_no / 19.0) + 0.6 * sin(sequence_no / 5.0),
    average_delay = 20 + (5.4 + 1.8 * sin(sequence_no / 19.0) + 0.6 * sin(sequence_no / 5.0)) * 2.4,
    average_speed = 48 - (5.4 + 1.8 * sin(sequence_no / 19.0) + 0.6 * sin(sequence_no / 5.0)) * 2.1,
    saturation = 45 + (5.4 + 1.8 * sin(sequence_no / 19.0) + 0.6 * sin(sequence_no / 5.0)) * 4.2,
    control_strategy = case mod(cast(floor((sequence_no - 1) / 8.0) as integer), 4)
        when 0 then 'Traffic-R1' when 1 then 'MaxPressure' when 2 then 'Traffic-R1' else 'FixedTime'
    end,
    device_status = case
        when 5.4 + 1.8 * sin(sequence_no / 19.0) + 0.6 * sin(sequence_no / 5.0) >= 7.0 then 'warning'
        when 5.4 + 1.8 * sin(sequence_no / 19.0) + 0.6 * sin(sequence_no / 5.0) >= 6.3 then 'maintenance'
        else 'normal'
    end,
    toast_id = case when mod(sequence_no, 120) = 0 then 900000 + sequence_no else null end,
    toast_title = case
        when mod(sequence_no, 600) = 0 then '拥堵事件告警'
        when mod(sequence_no, 120) = 0 then '路口状态更新'
        else null
    end,
    toast_body = case
        when mod(sequence_no, 600) = 0 then intersection_label || ' 排队长度偏高，当前策略保持连续控制。'
        when mod(sequence_no, 120) = 0 then intersection_label || ' 已完成新一轮数据库采样。'
        else null
    end,
    toast_tone = case
        when mod(sequence_no, 600) = 0 then 'rose'
        when mod(sequence_no, 120) = 0 then 'cyan'
        else null
    end;
