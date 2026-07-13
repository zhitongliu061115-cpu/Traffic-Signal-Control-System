alter table simulation_session add column warmup_seconds double precision;
alter table simulation_session add column created_at timestamp not null default current_timestamp;
alter table simulation_session add column started_at timestamp;
alter table simulation_session add column ended_at timestamp;
alter table simulation_session add column updated_at timestamp not null default current_timestamp;
alter table simulation_session add column config_payload text;
alter table simulation_session add column error_message text;

alter table simulation_frame add column captured_at timestamp not null default current_timestamp;
alter table simulation_frame add column status varchar(64);
alter table simulation_frame add column signal_count integer not null default 0;

alter table control_decision add column confidence double precision not null default 0;
alter table control_decision add column metadata text;
alter table control_decision add column created_at timestamp not null default current_timestamp;
alter table control_decision add column updated_at timestamp not null default current_timestamp;
alter table control_decision add column error_message text;

alter table control_decision_trace add column created_at timestamp not null default current_timestamp;

alter table traffic_r_inference_log add column request_id varchar(128);
alter table traffic_r_inference_log add column model_name varchar(128);
alter table traffic_r_inference_log add column response_payload text;
alter table traffic_r_inference_log add column status varchar(64) not null default 'UNKNOWN';
alter table traffic_r_inference_log add column created_at timestamp not null default current_timestamp;

create table traffic_r_inference_result (
    id uuid primary key,
    inference_log_id uuid not null references traffic_r_inference_log(id),
    intersection_id uuid not null references intersection(id),
    phase_id uuid references signal_phase(id),
    phase_code varchar(128),
    confidence double precision,
    valid boolean not null,
    reason text,
    raw_output text,
    created_at timestamp not null default current_timestamp,
    unique (inference_log_id, intersection_id)
);

create table intersection_movement_state_snapshot (
    id uuid primary key,
    frame_id uuid not null references simulation_frame(id),
    intersection_id uuid not null references intersection(id),
    movement_code varchar(16) not null,
    queue_len integer not null,
    vehicle_count integer not null,
    avg_wait_time double precision not null,
    avg_speed double precision,
    cell_1 integer not null,
    cell_2 integer not null,
    cell_3 integer not null,
    cell_4 integer not null,
    created_at timestamp not null default current_timestamp,
    unique (frame_id, intersection_id, movement_code)
);

alter table max_pressure_score add column created_at timestamp not null default current_timestamp;
alter table strategy_fallback_event add column created_at timestamp not null default current_timestamp;
alter table safety_constraint_event add column created_at timestamp not null default current_timestamp;

alter table emergency_event add column created_at timestamp not null default current_timestamp;
alter table emergency_event add column updated_at timestamp not null default current_timestamp;
alter table emergency_event add column ended_at timestamp;
alter table emergency_event add column error_message text;
alter table emergency_signal_event add column created_at timestamp not null default current_timestamp;

alter table agent_conversation add column external_session_id varchar(128);
alter table agent_conversation add column created_at timestamp not null default current_timestamp;
alter table agent_conversation add column updated_at timestamp not null default current_timestamp;
alter table agent_message add column created_at timestamp not null default current_timestamp;
alter table agent_tool_call add column error_message text;
alter table agent_tool_call add column created_at timestamp not null default current_timestamp;

alter table operation_audit_log add column error_message text;
alter table operation_audit_log add column created_at timestamp not null default current_timestamp;
alter table alert_event add column created_at timestamp not null default current_timestamp;
alter table alert_event add column updated_at timestamp not null default current_timestamp;

create index idx_simulation_frame_session_time on simulation_frame(session_id, sim_time);
create index idx_road_state_snapshot_lookup on road_state_snapshot(road_id, frame_id);
create index idx_intersection_state_snapshot_lookup on intersection_state_snapshot(intersection_id, frame_id);
create index idx_movement_snapshot_lookup on intersection_movement_state_snapshot(intersection_id, movement_code, frame_id);
create index idx_control_decision_intersection_time on control_decision(session_id, intersection_id, sim_time);
create index idx_traffic_r_inference_session_time on traffic_r_inference_log(session_id, sim_time);
create index idx_traffic_r_inference_result_log on traffic_r_inference_result(inference_log_id);
create index idx_strategy_fallback_session_time on strategy_fallback_event(session_id, sim_time);
create index idx_agent_tool_call_name_time on agent_tool_call(tool_name, created_at);
