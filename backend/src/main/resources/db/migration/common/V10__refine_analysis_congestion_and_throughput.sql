update analytics_metric
set detail = '今日 00:00 起全路网累计通过车辆数，每条数据库通行事件即时累加。'
where sequence_no = 1;

update analytics_monitoring_record
set electricity_kwh = case sequence_no
        when 1 then 410 when 2 then 530 when 3 then 360 when 4 then 610
        when 5 then 450 when 6 then 570 when 7 then 620 when 8 then 540
        when 9 then 460 when 10 then 590 when 11 then 430 else 560
    end,
    hvac_kwh = case sequence_no
        when 1 then 4.2 when 2 then 5.1 when 3 then 3.8 when 4 then 6.2
        when 5 then 4.7 when 6 then 5.8 when 7 then 7.8 when 8 then 6.9
        when 9 then 5.4 when 10 then 8.7 when 11 then 9.4 else 12.1
    end,
    chilled_water_return_temp = case sequence_no
        when 1 then 4.2 when 2 then 5.1 when 3 then 3.8 when 4 then 6.2
        when 5 then 4.7 when 6 then 5.8 when 7 then 7.8 when 8 then 6.9
        when 9 then 5.4 when 10 then 8.7 when 11 then 9.4 else 12.1
    end,
    control_strategy = case
        when sequence_no <= 8 then 'Traffic-R1'
        when sequence_no <= 10 then 'MaxPressure'
        else 'FixedTime'
    end;

update analytics_monitoring_record
set chilled_water_supply_temp = least(98, 38 + hvac_kwh * 4 + electricity_kwh / 55.0),
    env_humidity = least(98, 38 + hvac_kwh * 4 + electricity_kwh / 55.0),
    env_temperature = greatest(16, 52 - hvac_kwh * 2.5),
    occupancy_density = least(95, 32 + hvac_kwh * 4.2 + electricity_kwh / 70.0),
    water_m3 = 12 + hvac_kwh * 4.2,
    device_status = case
        when control_strategy = 'FixedTime' and hvac_kwh >= 8.6 and electricity_kwh >= 350 then 'warning'
        when control_strategy = 'MaxPressure' and hvac_kwh >= 9.2 and electricity_kwh >= 580 then 'warning'
        when control_strategy = 'Traffic-R1' and hvac_kwh >= 8.0 and electricity_kwh >= 620 then 'warning'
        when control_strategy = 'FixedTime' and hvac_kwh >= 7.0 then 'maintenance'
        when control_strategy = 'MaxPressure' and hvac_kwh >= 7.8 and electricity_kwh >= 500 then 'maintenance'
        when control_strategy = 'Traffic-R1' and hvac_kwh >= 6.8 and electricity_kwh >= 520 then 'maintenance'
        else 'normal'
    end;

alter table analytics_live_update
    add column passed_vehicles integer not null default 0;

update analytics_live_update
set passed_vehicles = 1,
    cumulative_traffic = 1180 + cast(sequence_no as integer),
    control_strategy = case mod(cast(floor((sequence_no - 1) / 8.0) as integer), 4)
        when 0 then 'Traffic-R1'
        when 1 then 'MaxPressure'
        when 2 then 'Traffic-R1'
        else 'FixedTime'
    end,
    inflow_count = 420 + 170 * sin(sequence_no / 31.0) + 65 * sin(sequence_no / 7.0);

update analytics_live_update
set queue_length = case control_strategy
        when 'Traffic-R1' then 2.6 + inflow_count / 135.0 + 0.9 * sin(sequence_no / 5.0)
        when 'MaxPressure' then 3.2 + inflow_count / 125.0 + 1.0 * sin(sequence_no / 5.0)
        else 4.8 + inflow_count / 90.0 + 1.2 * sin(sequence_no / 5.0)
    end;

update analytics_live_update
set average_delay = 12 + queue_length * 4.2,
    average_speed = greatest(15, 52 - queue_length * 2.5),
    saturation = least(98, 38 + queue_length * 4 + inflow_count / 55.0),
    device_status = case
        when mod(sequence_no, 997) = 0 then 'offline'
        when control_strategy = 'FixedTime' and queue_length >= 8.6 and inflow_count >= 350 then 'warning'
        when control_strategy = 'MaxPressure' and queue_length >= 9.2 and inflow_count >= 580 then 'warning'
        when control_strategy = 'Traffic-R1' and queue_length >= 8.0 and inflow_count >= 620 then 'warning'
        when control_strategy = 'FixedTime' and queue_length >= 7.0 then 'maintenance'
        when control_strategy = 'MaxPressure' and queue_length >= 7.8 and inflow_count >= 500 then 'maintenance'
        when control_strategy = 'Traffic-R1' and queue_length >= 6.8 and inflow_count >= 520 then 'maintenance'
        else 'normal'
    end;

update analytics_live_update
set health_score = case device_status
        when 'warning' then 86 when 'maintenance' then 90 when 'offline' then 88 else 94
    end,
    toast_id = case when mod(sequence_no, 120) = 0 then 900000 + sequence_no else null end,
    toast_title = case
        when mod(sequence_no, 120) = 0 and device_status = 'warning' then '拥堵事件告警'
        when mod(sequence_no, 120) = 0 then '路口状态更新'
        else null
    end,
    toast_body = case
        when mod(sequence_no, 120) = 0 and device_status = 'warning'
            then intersection_label || ' 在 ' || control_strategy || ' 控制下出现高到达流量与长排队。'
        when mod(sequence_no, 120) = 0 then intersection_label || ' 已完成新一轮数据库采样。'
        else null
    end,
    toast_tone = case
        when mod(sequence_no, 120) = 0 and device_status = 'warning' then 'rose'
        when mod(sequence_no, 120) = 0 then 'cyan'
        else null
    end;
