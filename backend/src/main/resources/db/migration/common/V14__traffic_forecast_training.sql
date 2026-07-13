create table traffic_forecast_observation (
    intersection_id varchar(64) not null,
    observed_at timestamp not null,
    observation_source varchar(32) not null,
    inflow_vehicles_per_hour double precision not null,
    queue_length_vehicles double precision not null,
    average_wait_seconds double precision not null,
    average_speed_kmh double precision not null,
    saturation_percent double precision not null,
    phase_name varchar(64) not null,
    control_strategy varchar(32) not null,
    device_status varchar(32) not null,
    quality_status varchar(32) not null default 'VALID',
    created_at timestamp not null default current_timestamp,
    primary key (intersection_id, observed_at, observation_source),
    check (inflow_vehicles_per_hour >= 0),
    check (queue_length_vehicles >= 0),
    check (average_wait_seconds >= 0),
    check (average_speed_kmh >= 0),
    check (saturation_percent >= 0 and saturation_percent <= 100)
);

create index idx_forecast_observation_time
    on traffic_forecast_observation(observed_at);

create index idx_forecast_observation_source_time
    on traffic_forecast_observation(observation_source, observed_at);

create table traffic_forecast_model_registry (
    model_version varchar(128) primary key,
    trained_at timestamp not null,
    data_started_at timestamp not null,
    data_ended_at timestamp not null,
    training_row_count bigint not null,
    source_summary varchar(512) not null,
    metrics_json text not null,
    artifact_uri varchar(512) not null,
    active boolean not null default false,
    created_at timestamp not null default current_timestamp
);

create index idx_forecast_model_active
    on traffic_forecast_model_registry(active, trained_at);
