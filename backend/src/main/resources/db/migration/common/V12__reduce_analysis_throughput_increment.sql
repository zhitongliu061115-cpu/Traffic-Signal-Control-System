update analytics_metric
set detail = '今日 00:00 起全路网累计通过车辆数，每 5 秒按数据库通行聚合事件累加 3-6 辆。'
where sequence_no = 1;

update analytics_live_update
set passed_vehicles = cast(round(greatest(3, least(6,
    1.8
    + inflow_count / 220.0
    + case control_strategy when 'Traffic-R1' then 0.9 when 'MaxPressure' then 0.5 else 0 end
    - case device_status when 'warning' then 0.6 when 'maintenance' then 0.2 else 0 end
))) as integer);

create table analytics_throughput_running_total_v12 as
select sequence_no,
       sum(passed_vehicles) over (
           order by sequence_no
           rows between unbounded preceding and current row
       ) as cumulative_increment
from analytics_live_update;

update analytics_live_update current_event
set cumulative_traffic = 1180 + cast((
    select running.cumulative_increment
    from analytics_throughput_running_total_v12 running
    where running.sequence_no = current_event.sequence_no
) as integer);

drop table analytics_throughput_running_total_v12;
