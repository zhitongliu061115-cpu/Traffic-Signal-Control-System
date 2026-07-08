create table cityflow_scene (
    id bigserial primary key,
    scene_id varchar(64) not null unique,
    scene_name varchar(128) not null,
    roadnet_file_path varchar(512),
    flow_file_path varchar(512),
    description varchar(512),
    created_at timestamp not null default current_timestamp
);

create table cityflow_intersection (
    id bigserial primary key,
    scene_id varchar(64) not null,
    intersection_id varchar(128) not null,
    x double precision not null,
    y double precision not null,
    virtual boolean not null,
    controlled boolean not null,
    unique (scene_id, intersection_id)
);

create table cityflow_road (
    id bigserial primary key,
    scene_id varchar(64) not null,
    road_id varchar(128) not null,
    start_intersection_id varchar(128) not null,
    end_intersection_id varchar(128) not null,
    points_json text not null,
    lane_count integer not null,
    lane_width double precision,
    max_speed double precision,
    unique (scene_id, road_id)
);

create table cityflow_road_link (
    id bigserial primary key,
    scene_id varchar(64) not null,
    intersection_id varchar(128) not null,
    road_link_index integer not null,
    start_road_id varchar(128) not null,
    end_road_id varchar(128) not null,
    type varchar(64) not null,
    lane_links_json text,
    unique (scene_id, intersection_id, road_link_index)
);

create table cityflow_phase (
    id bigserial primary key,
    scene_id varchar(64) not null,
    intersection_id varchar(128) not null,
    phase_index integer not null,
    phase_code varchar(64),
    duration integer,
    available_road_links_json text not null,
    unique (scene_id, intersection_id, phase_index)
);

create table simulation_session (
    id bigserial primary key,
    sid varchar(64) not null unique,
    scene_id varchar(64) not null,
    controller_type varchar(64) not null,
    status varchar(32) not null,
    sim_time double precision not null default 0,
    run_counts integer,
    step_interval double precision,
    decision_interval double precision,
    started_at timestamp,
    ended_at timestamp,
    created_at timestamp not null default current_timestamp
);

create table simulation_metric_snapshot (
    id bigserial primary key,
    sid varchar(64) not null,
    sim_time double precision not null,
    vehicle_count integer,
    queue_count integer,
    avg_speed double precision,
    avg_wait double precision,
    throughput integer,
    created_at timestamp not null default current_timestamp
);
