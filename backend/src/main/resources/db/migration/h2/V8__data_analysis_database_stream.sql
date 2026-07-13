alter table analytics_overview
    add column scatter_correlation double precision not null default 0.82;

create table analytics_metric_trend_point (
    metric_sequence_no integer not null references analytics_metric(sequence_no),
    point_sequence_no integer not null,
    point_value double precision not null,
    primary key (metric_sequence_no, point_sequence_no)
);

create table analytics_strategy_metric (
    sequence_no integer primary key,
    label varchar(64) not null,
    baseline_value double precision not null,
    max_pressure_value double precision not null,
    traffic_r1_value double precision not null,
    unit varchar(16) not null,
    lower_better boolean not null
);

create table analytics_stream_metadata (
    id integer primary key,
    dataset_started_at timestamp not null,
    poll_interval_ms integer not null
);

create table analytics_live_update (
    sequence_no bigint primary key,
    event_offset_seconds integer not null,
    sample_count integer not null,
    health_score integer not null,
    sampled_point_id varchar(64) not null,
    cumulative_traffic integer not null,
    average_queue double precision not null,
    average_wait double precision not null,
    adaptive_coverage double precision not null,
    alert_count integer not null,
    normal_count integer not null,
    slow_count integer not null,
    congested_count integer not null,
    offline_count integer not null,
    hour_label varchar(16) not null,
    hourly_flow double precision not null,
    hourly_saturation double precision not null,
    hourly_queue double precision not null,
    east_west_straight double precision not null,
    north_south_straight double precision not null,
    east_west_left double precision not null,
    north_south_left double precision not null,
    emergency_priority double precision not null,
    other_duration double precision not null,
    record_id bigint not null,
    intersection_label varchar(32) not null,
    intersection_id varchar(32) not null,
    inflow_count double precision not null,
    queue_length double precision not null,
    average_delay double precision not null,
    average_speed double precision not null,
    saturation double precision not null,
    phase_name varchar(64) not null,
    control_strategy varchar(32) not null,
    device_status varchar(32) not null,
    toast_id bigint,
    toast_title varchar(64),
    toast_body varchar(256),
    toast_tone varchar(16)
);

create index idx_analytics_live_update_sequence on analytics_live_update(sequence_no);

insert into analytics_metric_trend_point (metric_sequence_no, point_sequence_no, point_value) values
(1, 1, 58420), (1, 2, 60280), (1, 3, 61860), (1, 4, 62740), (1, 5, 63810), (1, 6, 65190), (1, 7, 66780),
(2, 1, 7.9), (2, 2, 8.3), (2, 3, 7.6), (2, 4, 8.8), (2, 5, 9.2), (2, 6, 8.5), (2, 7, 8.1),
(3, 1, 42), (3, 2, 45), (3, 3, 41), (3, 4, 48), (3, 5, 46), (3, 6, 44), (3, 7, 43),
(4, 1, 82.4), (4, 2, 83.1), (4, 3, 83.3), (4, 4, 84.0), (4, 5, 84.6), (4, 6, 85.2), (4, 7, 85.8),
(5, 1, 2), (5, 2, 2), (5, 3, 3), (5, 4, 3), (5, 5, 4), (5, 6, 4), (5, 7, 4);

insert into analytics_strategy_metric (
    sequence_no, label, baseline_value, max_pressure_value, traffic_r1_value, unit, lower_better
) values
(1, '平均排队长度', 18, 12.4, 9.7, '辆', true),
(2, '累计排队车辆数', 1260, 880, 690, '辆', true),
(3, '平均等待时间', 52, 38, 31, '秒', true),
(4, '平均旅行时间', 238, 209, 196, '秒', true),
(5, '通行量', 7200, 7900, 8350, '辆/h', false);

insert into analytics_stream_metadata (id, dataset_started_at, poll_interval_ms) values
(1, timestamp '2026-07-13 08:00:00', 2000);

create table analytics_live_seed_measurement as
select
    series.n as n,
    8.4 + 2.8 * sin(series.n / 37.0) + 0.9 * sin(series.n / 11.0) as queue_value,
    35 + 14 * sin(series.n / 41.0) + 5 * sin(series.n / 13.0) as wait_value,
    920 + 310 * sin(series.n / 31.0) + 120 * sin(series.n / 7.0) as flow_value,
    86 + 12 * sin(series.n / 29.0) + 4 * sin(series.n / 9.0) as saturation_value
from system_range(1, 10000) series(n);

insert into analytics_live_update (
    sequence_no, event_offset_seconds, sample_count, health_score, sampled_point_id,
    cumulative_traffic, average_queue, average_wait, adaptive_coverage, alert_count,
    normal_count, slow_count, congested_count, offline_count,
    hour_label, hourly_flow, hourly_saturation, hourly_queue,
    east_west_straight, north_south_straight, east_west_left, north_south_left,
    emergency_priority, other_duration,
    record_id, intersection_label, intersection_id, inflow_count, queue_length,
    average_delay, average_speed, saturation, phase_name, control_strategy, device_status,
    toast_id, toast_title, toast_body, toast_tone
)
select
    n,
    n * 2,
    34752 + n * 3,
    case when queue_value >= 10.8 then 84 when queue_value >= 8.2 then 89 else 94 end,
    case mod(n, 12)
        when 0 then 'intersection_3_4-' || cast(n as varchar)
        when 1 then 'intersection_1_1-' || cast(n as varchar)
        when 2 then 'intersection_1_2-' || cast(n as varchar)
        when 3 then 'intersection_1_3-' || cast(n as varchar)
        when 4 then 'intersection_1_4-' || cast(n as varchar)
        when 5 then 'intersection_2_1-' || cast(n as varchar)
        when 6 then 'intersection_2_2-' || cast(n as varchar)
        when 7 then 'intersection_2_3-' || cast(n as varchar)
        when 8 then 'intersection_2_4-' || cast(n as varchar)
        when 9 then 'intersection_3_1-' || cast(n as varchar)
        when 10 then 'intersection_3_2-' || cast(n as varchar)
        else 'intersection_3_3-' || cast(n as varchar)
    end,
    64280 + n * 3 + mod(n, 7),
    queue_value,
    wait_value,
    83.3 + mod(n, 86) / 10.0,
    4 + cast(floor(n / 600.0) as integer),
    case when queue_value >= 10.8 then 6 when queue_value >= 8.2 then 7 else 8 end,
    case when queue_value >= 10.8 then 3 else 2 end,
    case when queue_value >= 10.8 then 2 else 1 end,
    case when mod(n, 97) = 0 then 1 else 0 end,
    case mod(n, 4) when 0 then '00:00' when 1 then '06:00' when 2 then '12:00' else '18:00' end,
    flow_value,
    saturation_value,
    queue_value,
    293760 + n * 11,
    276480 + n * 10,
    120960 + n * 5,
    120960 + n * 5,
    8640 + n,
    51840 + n * 2,
    400000 + n,
    '路口 ' || cast(cast(floor(mod(n - 1, 12) / 4.0) + 1 as integer) as varchar)
        || '-' || cast(mod(n - 1, 4) + 1 as varchar),
    'intersection_' || cast(cast(floor(mod(n - 1, 12) / 4.0) + 1 as integer) as varchar)
        || '_' || cast(mod(n - 1, 4) + 1 as varchar),
    flow_value,
    queue_value,
    wait_value,
    52 - queue_value * 2.4 + 3 * sin(n / 17.0),
    saturation_value,
    case mod(n, 4) when 0 then '东西直行' when 1 then '南北直行' when 2 then '东西左转' else '南北左转' end,
    case mod(n, 5) when 0 then 'FixedTime' when 1 then 'MaxPressure' when 2 then 'RL' when 3 then 'Traffic-R1' else '应急绿波' end,
    case when queue_value >= 10.8 then 'warning' when mod(n, 97) = 0 then 'offline' when queue_value >= 9.8 then 'maintenance' else 'normal' end,
    case when mod(n, 25) = 0 then 900000 + n else null end,
    case when mod(n, 100) = 0 then '拥堵事件告警' when mod(n, 25) = 0 then '数据库采样更新' else null end,
    case when mod(n, 100) = 0
        then '路口排队长度达到 ' || cast(queue_value as varchar) || ' 辆，请关注控制策略。'
        when mod(n, 25) = 0
        then '已从云端数据库读取第 ' || cast(n as varchar) || ' 条顺序采样。'
        else null
    end,
    case when mod(n, 100) = 0 then 'rose' when mod(n, 25) = 0 then 'cyan' else null end
from analytics_live_seed_measurement;

drop table analytics_live_seed_measurement;
