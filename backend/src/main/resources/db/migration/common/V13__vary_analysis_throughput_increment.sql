update analytics_metric
set detail = '今日 00:00 起全路网累计通过车辆数，每 5 秒按数据库通行聚合事件随机增加 3-6 辆。'
where sequence_no = 1;

update analytics_live_update
set passed_vehicles = 3 + cast(mod(cast(floor(abs(sin(
    sequence_no * 12.9898
    + inflow_count * 0.017
    + case control_strategy
        when 'Traffic-R1' then 1.7
        when 'MaxPressure' then 3.1
        when 'FixedTime' then 4.3
        else 5.9
      end
    + case device_status
        when 'warning' then 7.3
        when 'maintenance' then 2.6
        when 'offline' then 9.1
        else 0.4
      end
)) * 100000) as bigint), 4) as integer);

create table analytics_throughput_running_total_v13 as
select sequence_no,
       sum(passed_vehicles) over (
           order by sequence_no
           rows between unbounded preceding and current row
       ) as cumulative_increment
from analytics_live_update;

update analytics_live_update current_event
set cumulative_traffic = 1180 + cast((
    select running.cumulative_increment
    from analytics_throughput_running_total_v13 running
    where running.sequence_no = current_event.sequence_no
) as integer);

drop table analytics_throughput_running_total_v13;
