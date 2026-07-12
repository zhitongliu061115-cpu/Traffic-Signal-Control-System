create table if not exists simulation_run (
    id uuid primary key,
    sid varchar(128) not null unique,
    scene_id varchar(128) not null,
    controller_type varchar(64) not null,
    speed double precision,
    status varchar(32) not null,
    created_at timestamp with time zone not null default current_timestamp,
    started_at timestamp with time zone,
    ended_at timestamp with time zone
);

create table if not exists simulation_metric_sample (
    id uuid primary key,
    run_id uuid not null references simulation_run(id) on delete cascade,
    seq bigint not null,
    sim_time double precision not null,
    recorded_at timestamp with time zone not null default current_timestamp,
    vehicle_count integer not null,
    active_vehicle_count integer,
    scheduled_departure_count integer,
    queue_count integer not null,
    avg_speed double precision not null,
    avg_wait double precision not null,
    throughput integer not null,
    unique (run_id, seq)
);

create table if not exists simulation_road_sample (
    sample_id uuid not null references simulation_metric_sample(id) on delete cascade,
    road_id varchar(128) not null,
    vehicle_count integer not null,
    queue_count integer not null,
    avg_speed double precision not null,
    level varchar(32) not null,
    primary key (sample_id, road_id)
);

create table if not exists simulation_intersection_sample (
    sample_id uuid not null references simulation_metric_sample(id) on delete cascade,
    intersection_id varchar(128) not null,
    vehicle_count integer not null,
    queue_count integer not null,
    avg_wait double precision not null,
    level varchar(32) not null,
    phase_code varchar(128),
    primary key (sample_id, intersection_id)
);

create index if not exists idx_simulation_run_scene_strategy
    on simulation_run(scene_id, controller_type, created_at);
create index if not exists idx_simulation_metric_recorded_at
    on simulation_metric_sample(recorded_at desc);
create index if not exists idx_simulation_metric_run_time
    on simulation_metric_sample(run_id, sim_time);
