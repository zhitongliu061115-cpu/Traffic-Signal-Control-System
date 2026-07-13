alter table control_decision add column decision_key varchar(64);
alter table control_decision add column input_frame_id uuid references simulation_frame(id);

update control_decision set decision_key = cast(id as varchar) where decision_key is null;
alter table control_decision alter column decision_key set not null;

create unique index idx_control_decision_key on control_decision(decision_key);
create index idx_control_decision_effect_pending
    on control_decision(session_id, controller_type, status, sim_time);

create table control_decision_effect (
    id uuid primary key,
    decision_id uuid not null unique references control_decision(id),
    before_frame_id uuid not null references simulation_frame(id),
    after_frame_id uuid not null references simulation_frame(id),
    horizon_sec integer not null,
    queue_before integer not null,
    queue_after integer not null,
    queue_delta integer not null,
    avg_wait_before double precision not null,
    avg_wait_after double precision not null,
    avg_wait_delta double precision not null,
    avg_speed_before double precision not null,
    avg_speed_after double precision not null,
    avg_speed_delta double precision not null,
    throughput_before integer not null,
    throughput_after integer not null,
    throughput_delta integer not null,
    evaluation_label varchar(64) not null,
    detail_payload text,
    created_at timestamp not null default current_timestamp
);

create index idx_control_decision_effect_created_at
    on control_decision_effect(created_at);
