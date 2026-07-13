from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any

from .repository import ForecastRepository


SEED_SQL = """
with intersections(intersection_id, demand_factor, capacity, index_no) as (
    values
        ('intersection_1_1', 0.78, 1180.0, 1),
        ('intersection_1_2', 0.84, 1160.0, 2),
        ('intersection_1_3', 0.91, 1120.0, 3),
        ('intersection_1_4', 1.02, 1080.0, 4),
        ('intersection_2_1', 0.88, 1140.0, 5),
        ('intersection_2_2', 0.96, 1100.0, 6),
        ('intersection_2_3', 1.05, 1060.0, 7),
        ('intersection_2_4', 1.14, 1020.0, 8),
        ('intersection_3_1', 0.98, 1080.0, 9),
        ('intersection_3_2', 1.10, 1030.0, 10),
        ('intersection_3_3', 1.18, 990.0, 11),
        ('intersection_3_4', 1.26, 950.0, 12)
), minute_grid as (
    select series.observed_at, intersections.*,
           extract(hour from series.observed_at) * 60 + extract(minute from series.observed_at) as minute_of_day,
           extract(isodow from series.observed_at) as day_of_week,
           floor(extract(epoch from series.observed_at) / 60.0) as epoch_minute
    from generate_series(%s::timestamp, %s::timestamp, interval '1 minute') as series(observed_at)
    cross join intersections
), demand as (
    select *,
           exp(-power((minute_of_day - 480.0) / 95.0, 2)) as morning_peak,
           exp(-power((minute_of_day - 1050.0) / 115.0, 2)) as evening_peak,
           case when day_of_week <= 5 then 1.0 else 0.72 end as weekday_factor,
           case mod(cast(floor(epoch_minute / 720.0) as bigint) + index_no, 4)
               when 0 then 'FixedTime'
               when 1 then 'MaxPressure'
               else 'Traffic-R1'
           end as strategy
    from minute_grid
), flow_values as (
    select *, greatest(35.0, least(1550.0,
               demand_factor * weekday_factor * (
                   145.0 + 640.0 * morning_peak + 790.0 * evening_peak
                   + 95.0 * sin((minute_of_day - 240.0) / 180.0)
               )
               + 34.0 * sin(epoch_minute / 17.0 + index_no * 0.7)
               + 18.0 * sin(epoch_minute / 5.0 + index_no * 1.9)
           )) as flow_value
    from demand
), queue_values as (
    select *, greatest(0.4, least(24.0,
               1.1
               + greatest(0.0, flow_value / capacity - 0.42) * 15.5
               + morning_peak * 1.4 + evening_peak * 2.1
               + 0.55 * sin(epoch_minute / 7.0 + index_no)
               + case strategy when 'FixedTime' then 2.0 when 'MaxPressure' then 0.9 else 0.2 end
           )) as queue_value
    from flow_values
), measurements as (
    select *,
           greatest(5.0, least(130.0,
               8.0 + queue_value * 3.25 + flow_value / capacity * 8.0
               + 1.8 * sin(epoch_minute / 9.0 + index_no)
           )) as wait_value,
           greatest(7.0, least(58.0,
               53.0 - queue_value * 2.15 - greatest(0.0, flow_value / capacity - 0.7) * 12.0
           )) as speed_value,
           greatest(3.0, least(100.0, flow_value / capacity * 88.0 + queue_value * 1.1)) as saturation_value
    from queue_values
)
insert into traffic_forecast_observation (
    intersection_id, observed_at, observation_source,
    inflow_vehicles_per_hour, queue_length_vehicles, average_wait_seconds,
    average_speed_kmh, saturation_percent, phase_name, control_strategy,
    device_status, quality_status
)
select intersection_id,
       observed_at,
       'SYNTHETIC',
       round(flow_value::numeric, 3),
       round(queue_value::numeric, 3),
       round(wait_value::numeric, 3),
       round(speed_value::numeric, 3),
       round(saturation_value::numeric, 3),
       case mod(cast(epoch_minute / 2 as bigint) + index_no, 4)
           when 0 then '东西直行'
           when 1 then '南北直行'
           when 2 then '东西左转'
           else '南北左转'
       end,
       strategy,
       case
           when mod(cast(epoch_minute as bigint) + index_no * 137, 10007) = 0 then 'offline'
           when queue_value >= 11.6 then 'warning'
           when queue_value >= 8.2 then 'maintenance'
           else 'normal'
       end,
       'VALID'
from measurements
on conflict (intersection_id, observed_at, observation_source) do update set
    inflow_vehicles_per_hour = excluded.inflow_vehicles_per_hour,
    queue_length_vehicles = excluded.queue_length_vehicles,
    average_wait_seconds = excluded.average_wait_seconds,
    average_speed_kmh = excluded.average_speed_kmh,
    saturation_percent = excluded.saturation_percent,
    phase_name = excluded.phase_name,
    control_strategy = excluded.control_strategy,
    device_status = excluded.device_status,
    quality_status = excluded.quality_status
"""


def seed_synthetic(repository: ForecastRepository, days: int, replace: bool = False) -> dict[str, Any]:
    if days < 7:
        raise ValueError("days must be at least 7")
    ended_at = datetime.now().replace(second=0, microsecond=0) - timedelta(minutes=1)
    started_at = ended_at - timedelta(days=days) + timedelta(minutes=1)
    with repository._connect() as connection, connection.cursor() as cursor:
        if replace:
            cursor.execute(
                "delete from traffic_forecast_observation where observation_source = 'SYNTHETIC'"
            )
        cursor.execute(SEED_SQL, (started_at, ended_at))
        affected = cursor.rowcount
        connection.commit()
    return {
        "source": "SYNTHETIC",
        "startedAt": started_at.isoformat(),
        "endedAt": ended_at.isoformat(),
        "affectedRows": affected,
        "profile": repository.profile(),
    }
