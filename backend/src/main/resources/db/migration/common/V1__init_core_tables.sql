create table scene (
    id uuid primary key,
    scene_code varchar(128) not null unique,
    name varchar(128) not null,
    source_type varchar(64) not null,
    cityflow_roadnet_path varchar(512) not null,
    cityflow_flow_path varchar(512) not null,
    map_provider varchar(64),
    coordinate_system varchar(64)
);

create table intersection (
    id uuid primary key,
    scene_id uuid not null references scene(id),
    cityflow_id varchar(128) not null,
    map_intersection_id varchar(128),
    name varchar(128),
    type varchar(64),
    virtual boolean not null,
    longitude decimal(10, 7),
    latitude decimal(10, 7),
    x double precision not null,
    y double precision not null,
    unique (scene_id, cityflow_id)
);

create table road (
    id uuid primary key,
    scene_id uuid not null references scene(id),
    cityflow_id varchar(128) not null,
    from_intersection_id uuid not null references intersection(id),
    to_intersection_id uuid not null references intersection(id),
    name varchar(128),
    direction varchar(64),
    length_m double precision not null,
    speed_limit double precision,
    lane_count integer not null,
    geometry text not null,
    unique (scene_id, cityflow_id)
);

create table lane (
    id uuid primary key,
    road_id uuid not null references road(id),
    cityflow_lane_index integer not null,
    lane_code varchar(128) not null,
    direction varchar(64) not null,
    movement varchar(64) not null,
    width double precision,
    speed_limit double precision,
    unique (road_id, cityflow_lane_index)
);

create table road_link (
    id uuid primary key,
    intersection_id uuid not null references intersection(id),
    cityflow_index integer not null,
    from_road_id uuid not null references road(id),
    to_road_id uuid not null references road(id),
    movement_type varchar(64) not null,
    unique (intersection_id, cityflow_index)
);

create table lane_link (
    id uuid primary key,
    road_link_id uuid not null references road_link(id),
    start_lane_id uuid not null references lane(id),
    end_lane_id uuid not null references lane(id),
    geometry text,
    unique (road_link_id, start_lane_id, end_lane_id)
);

create table signal_phase (
    id uuid primary key,
    intersection_id uuid not null references intersection(id),
    phase_index integer not null,
    phase_code varchar(128) not null,
    phase_name varchar(128),
    phase_type varchar(64),
    default_green_sec integer not null,
    yellow_sec integer not null,
    all_red_sec integer not null,
    unique (intersection_id, phase_index)
);

create table signal_phase_road_link (
    phase_id uuid not null references signal_phase(id),
    road_link_id uuid not null references road_link(id),
    primary key (phase_id, road_link_id)
);

create table signal_timing_plan (
    id uuid primary key,
    intersection_id uuid not null references intersection(id),
    plan_code varchar(128) not null,
    name varchar(128) not null,
    source varchar(64) not null,
    cycle_sec integer not null,
    offset_sec integer not null,
    status varchar(64) not null,
    unique (intersection_id, plan_code)
);

create table signal_timing_plan_phase (
    id uuid primary key,
    plan_id uuid not null references signal_timing_plan(id),
    phase_id uuid not null references signal_phase(id),
    sequence_no integer not null,
    green_sec integer not null,
    unique (plan_id, sequence_no),
    unique (plan_id, phase_id)
);

create table safety_constraint (
    id uuid primary key,
    intersection_id uuid references intersection(id),
    constraint_type varchar(64) not null,
    min_value double precision not null,
    max_value double precision not null,
    config_payload text
);

create table phase_transition_rule (
    id uuid primary key,
    intersection_id uuid not null references intersection(id),
    from_phase_id uuid not null references signal_phase(id),
    to_phase_id uuid not null references signal_phase(id),
    allowed boolean not null,
    transition_yellow_sec integer not null,
    transition_all_red_sec integer not null,
    unique (intersection_id, from_phase_id, to_phase_id)
);

create table simulation_session (
    id uuid primary key,
    sid varchar(128) not null unique,
    scene_id uuid not null references scene(id),
    controller_type varchar(64) not null,
    speed double precision,
    status varchar(64) not null
);

create table simulation_frame (
    id uuid primary key,
    session_id uuid not null references simulation_session(id),
    seq bigint not null,
    sim_time double precision not null,
    vehicle_count integer not null,
    queue_count integer not null,
    avg_speed double precision not null,
    avg_wait double precision not null,
    throughput integer not null,
    unique (session_id, seq)
);

create table road_state_snapshot (
    id uuid primary key,
    frame_id uuid not null references simulation_frame(id),
    road_id uuid not null references road(id),
    vehicle_count integer not null,
    queue_count integer not null,
    avg_speed double precision not null,
    level varchar(64) not null,
    unique (frame_id, road_id)
);

create table lane_state_snapshot (
    id uuid primary key,
    frame_id uuid not null references simulation_frame(id),
    lane_id uuid not null references lane(id),
    queue_len integer not null,
    vehicle_count integer not null,
    avg_wait_time double precision not null,
    cell_1 integer not null,
    cell_2 integer not null,
    cell_3 integer not null,
    cell_4 integer not null,
    unique (frame_id, lane_id)
);

create table intersection_state_snapshot (
    id uuid primary key,
    frame_id uuid not null references simulation_frame(id),
    intersection_id uuid not null references intersection(id),
    queue_count integer not null,
    avg_wait double precision not null,
    level varchar(64) not null,
    current_phase_id uuid not null references signal_phase(id),
    unique (frame_id, intersection_id)
);

create table vehicle_state_snapshot (
    id uuid primary key,
    frame_id uuid not null references simulation_frame(id),
    vehicle_id varchar(128) not null,
    road_id uuid not null references road(id),
    lane_id uuid references lane(id),
    x double precision not null,
    y double precision not null,
    angle double precision not null,
    speed double precision not null,
    vehicle_type varchar(64) not null,
    unique (frame_id, vehicle_id)
);

create table control_decision (
    id uuid primary key,
    session_id uuid not null references simulation_session(id),
    intersection_id uuid not null references intersection(id),
    sim_time double precision not null,
    controller_type varchar(64) not null,
    requested_phase_id uuid references signal_phase(id),
    final_phase_id uuid not null references signal_phase(id),
    duration_sec integer not null,
    status varchar(64) not null,
    reason text not null
);

create table control_decision_trace (
    id uuid primary key,
    decision_id uuid not null references control_decision(id),
    stage varchar(64) not null,
    input_payload text,
    output_payload text,
    message text
);

create table traffic_r_inference_log (
    id uuid primary key,
    session_id uuid not null references simulation_session(id),
    sim_time double precision not null,
    request_payload text not null,
    prompt_text text not null,
    raw_output text not null,
    parsed_phase_code varchar(128) not null,
    valid boolean not null,
    latency_ms integer not null,
    error_message text
);

create table max_pressure_score (
    id uuid primary key,
    decision_id uuid not null references control_decision(id),
    phase_id uuid not null references signal_phase(id),
    pressure_score double precision not null,
    detail_payload text,
    unique (decision_id, phase_id)
);

create table strategy_fallback_event (
    id uuid primary key,
    session_id uuid not null references simulation_session(id),
    intersection_id uuid not null references intersection(id),
    from_strategy varchar(64) not null,
    to_strategy varchar(64) not null,
    reason text not null,
    sim_time double precision not null
);

create table safety_constraint_event (
    id uuid primary key,
    decision_id uuid not null references control_decision(id),
    constraint_type varchar(64) not null,
    action varchar(64) not null,
    before_phase_id uuid references signal_phase(id),
    after_phase_id uuid references signal_phase(id),
    reason text not null
);

create table control_region (
    id uuid primary key,
    scene_id uuid not null references scene(id),
    region_code varchar(128) not null,
    name varchar(128) not null,
    controller_type varchar(64) not null,
    region_type varchar(64) not null,
    unique (scene_id, region_code)
);

create table control_region_intersection (
    region_id uuid not null references control_region(id),
    intersection_id uuid not null references intersection(id),
    role varchar(64) not null,
    primary key (region_id, intersection_id)
);

create table emergency_event (
    id uuid primary key,
    session_id uuid not null references simulation_session(id),
    event_code varchar(128) not null unique,
    vehicle_id varchar(128) not null,
    vehicle_type varchar(64) not null,
    priority integer not null,
    status varchar(64) not null,
    start_coord text not null,
    end_coord text not null
);

create table emergency_route_node (
    id uuid primary key,
    emergency_event_id uuid not null references emergency_event(id),
    sequence_no integer not null,
    intersection_id uuid not null references intersection(id),
    road_id uuid references road(id),
    planned_arrival_time double precision,
    actual_arrival_time double precision,
    unique (emergency_event_id, sequence_no)
);

create table emergency_signal_event (
    id uuid primary key,
    emergency_event_id uuid not null references emergency_event(id),
    intersection_id uuid not null references intersection(id),
    sim_time double precision not null,
    action_type varchar(64) not null,
    phase_id_before uuid references signal_phase(id),
    phase_id_after uuid references signal_phase(id),
    reason text not null
);

create table agent_conversation (
    id uuid primary key,
    user_id uuid,
    session_id uuid references simulation_session(id),
    title varchar(128) not null
);

create table agent_message (
    id uuid primary key,
    conversation_id uuid not null references agent_conversation(id),
    role varchar(64) not null,
    content text not null
);

create table agent_tool_call (
    id uuid primary key,
    message_id uuid not null references agent_message(id),
    tool_name varchar(128) not null,
    arguments_payload text not null,
    result_payload text,
    status varchar(64) not null,
    latency_ms integer not null
);

create table operation_audit_log (
    id uuid primary key,
    actor_type varchar(64) not null,
    actor_id varchar(128) not null,
    operation_type varchar(128) not null,
    target_type varchar(128) not null,
    target_id varchar(128) not null,
    request_payload text not null,
    result_status varchar(64) not null
);

create table alert_event (
    id uuid primary key,
    session_id uuid references simulation_session(id),
    alert_type varchar(128) not null,
    level varchar(64) not null,
    target_type varchar(128) not null,
    target_id varchar(128) not null,
    title varchar(256) not null,
    description text not null,
    status varchar(64) not null
);

create table service_health_snapshot (
    id uuid primary key,
    service_name varchar(128) not null,
    status varchar(64) not null,
    latency_ms integer not null,
    detail_payload text,
    checked_at timestamp not null
);

create index idx_intersection_scene on intersection(scene_id);
create index idx_road_scene on road(scene_id);
create index idx_road_from_to on road(from_intersection_id, to_intersection_id);
create index idx_lane_road on lane(road_id);
create index idx_signal_phase_intersection on signal_phase(intersection_id);
create index idx_simulation_frame_session on simulation_frame(session_id, seq);
create index idx_control_decision_session_time on control_decision(session_id, sim_time);
create index idx_alert_event_session on alert_event(session_id);
create index idx_service_health_checked_at on service_health_snapshot(checked_at);
