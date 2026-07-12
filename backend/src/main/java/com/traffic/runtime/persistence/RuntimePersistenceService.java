package com.traffic.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.roadnet.dto.IntersectionDto;
import com.traffic.roadnet.dto.PhaseDto;
import com.traffic.roadnet.dto.PointDto;
import com.traffic.roadnet.dto.RoadDto;
import com.traffic.roadnet.dto.RoadLinkDto;
import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.simulation.dto.IntersectionLaneStateDto;
import com.traffic.simulation.dto.IntersectionStateDto;
import com.traffic.simulation.dto.LaneMovementStateDto;
import com.traffic.simulation.dto.RoadStateDto;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SimulationMetricsDto;
import com.traffic.simulation.dto.VehicleStateDto;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.strategy.dto.ControlDecision;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RuntimePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(RuntimePersistenceService.class);
    private static final int DEFAULT_PHASE_GREEN_SEC = 10;
    private static final int DEFAULT_PHASE_YELLOW_SEC = 3;
    private static final int DEFAULT_PHASE_ALL_RED_SEC = 1;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RuntimePersistenceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void ensureRoadnet(String sceneId, Supplier<RoadnetResponse> roadnetSupplier) {
        try {
            RoadnetResponse roadnet = roadnetSupplier.get();
            if (roadnet == null) {
                ensureScene(sceneId);
                return;
            }
            UUID scenePk = ensureScene(sceneId);
            ensureIntersections(scenePk, roadnet.intersections());
            ensureRoadsAndLanes(scenePk, roadnet.roads());
            ensureRoadLinks(scenePk, roadnet.roadLinks());
            ensurePhases(scenePk, roadnet.phases());
        } catch (RuntimeException ex) {
            log.warn("runtime roadnet persistence skipped. sceneId={}, error={}", sceneId, ex.getMessage());
            try {
                ensureScene(sceneId);
            } catch (RuntimeException inner) {
                log.warn("runtime fallback scene persistence skipped. sceneId={}, error={}", sceneId, inner.getMessage());
            }
        }
    }

    public void createSession(
            String sid,
            String sceneId,
            String controllerType,
            Double speed,
            Double warmupSeconds,
            String status
    ) {
        try {
            UUID scenePk = ensureScene(sceneId);
            Optional<UUID> existing = findSimulationSessionId(sid);
            if (existing.isPresent()) {
                jdbcTemplate.update("""
                        update simulation_session
                        set scene_id = ?, controller_type = ?, speed = ?, warmup_seconds = ?,
                            status = ?, config_payload = ?, updated_at = current_timestamp
                        where id = ?
                        """,
                        scenePk,
                        controllerType,
                        speed,
                        warmupSeconds,
                        status,
                        sessionConfigPayload(sceneId, controllerType, speed, warmupSeconds),
                        existing.get()
                );
                return;
            }
            jdbcTemplate.update("""
                    insert into simulation_session (
                        id, sid, scene_id, controller_type, speed, warmup_seconds, status, config_payload
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    sid,
                    scenePk,
                    controllerType,
                    speed,
                    warmupSeconds,
                    status,
                    sessionConfigPayload(sceneId, controllerType, speed, warmupSeconds)
            );
        } catch (RuntimeException ex) {
            log.warn("runtime session persistence skipped. sid={}, sceneId={}, error={}", sid, sceneId, ex.getMessage());
        }
    }

    public void updateSessionStatus(String sid, String status) {
        try {
            jdbcTemplate.update("""
                    update simulation_session
                    set status = ?,
                        started_at = case
                            when ? = 'running' and started_at is null then current_timestamp
                            else started_at
                        end,
                        ended_at = case
                            when ? = 'finished' then current_timestamp
                            else ended_at
                        end,
                        updated_at = current_timestamp
                    where sid = ?
                    """, status, status, status, sid);
        } catch (RuntimeException ex) {
            log.warn("runtime session status persistence skipped. sid={}, status={}, error={}", sid, status, ex.getMessage());
        }
    }

    public void persistFrame(
            SimulationRuntimeSession session,
            long seq,
            SimFrameData frame,
            List<ControlDecision> decisions
    ) {
        if (session == null || frame == null) {
            return;
        }
        try {
            UUID scenePk = ensureScene(session.getSceneId());
            UUID sessionPk = ensureSessionExists(session, scenePk);
            UUID framePk = upsertFrame(sessionPk, seq, frame);
            persistRoadSnapshots(scenePk, framePk, frame.roads());
            persistMovementSnapshots(scenePk, framePk, frame.laneStates());
            persistIntersectionSnapshots(scenePk, framePk, frame.intersections(), frame.signals());
            persistVehicleSnapshots(scenePk, framePk, frame.vehicles());
            persistDecisions(scenePk, sessionPk, frame, decisions);
        } catch (RuntimeException ex) {
            log.warn(
                    "runtime frame persistence skipped. sid={}, seq={}, simTime={}, error={}",
                    session.getSid(),
                    seq,
                    frame.simTime(),
                    ex.getMessage()
            );
        }
    }

    public void persistRuntimeEvents(
            SimulationRuntimeSession session,
            SimFrameData frame,
            List<ControlDecision> decisions
    ) {
        if (session == null || frame == null) {
            return;
        }
        try {
            UUID scenePk = ensureScene(session.getSceneId());
            UUID sessionPk = ensureSessionExists(session, scenePk);
            persistDecisions(scenePk, sessionPk, frame, decisions);
        } catch (RuntimeException ex) {
            log.warn(
                    "runtime event persistence skipped. sid={}, simTime={}, error={}",
                    session.getSid(),
                    frame.simTime(),
                    ex.getMessage()
            );
        }
    }

    private void ensureIntersections(UUID scenePk, List<IntersectionDto> intersections) {
        if (intersections == null) {
            return;
        }
        intersections.stream()
                .filter(Objects::nonNull)
                .forEach(intersection -> ensureIntersection(
                        scenePk,
                        intersection.id(),
                        intersection.x(),
                        intersection.y(),
                        intersection.virtual()
                ));
    }

    private void ensureRoadsAndLanes(UUID scenePk, List<RoadDto> roads) {
        if (roads == null) {
            return;
        }
        roads.stream()
                .filter(Objects::nonNull)
                .forEach(road -> {
                    UUID fromIntersectionPk = ensureIntersection(scenePk, road.from(), 0.0, 0.0, true);
                    UUID toIntersectionPk = ensureIntersection(scenePk, road.to(), 0.0, 0.0, true);
                    UUID roadPk = ensureRoad(scenePk, road, fromIntersectionPk, toIntersectionPk);
                    int laneCount = Math.max(0, road.laneCount());
                    for (int laneIndex = 0; laneIndex < laneCount; laneIndex++) {
                        ensureLane(roadPk, road.id(), laneIndex);
                    }
                });
    }

    private void ensureRoadLinks(UUID scenePk, List<RoadLinkDto> roadLinks) {
        if (roadLinks == null) {
            return;
        }
        roadLinks.stream()
                .filter(Objects::nonNull)
                .forEach(roadLink -> {
                    Optional<UUID> intersectionPk = findIntersectionId(scenePk, roadLink.intersectionId());
                    Optional<UUID> fromRoadPk = findRoadId(scenePk, roadLink.fromRoadId());
                    Optional<UUID> toRoadPk = findRoadId(scenePk, roadLink.toRoadId());
                    if (intersectionPk.isEmpty() || fromRoadPk.isEmpty() || toRoadPk.isEmpty()) {
                        return;
                    }
                    ensureRoadLink(intersectionPk.get(), roadLink, fromRoadPk.get(), toRoadPk.get());
                });
    }

    private void ensurePhases(UUID scenePk, List<PhaseDto> phases) {
        if (phases == null) {
            return;
        }
        phases.stream()
                .filter(Objects::nonNull)
                .forEach(phase -> {
                    UUID intersectionPk = ensureIntersection(scenePk, phase.intersectionId(), 0.0, 0.0, true);
                    UUID phasePk = ensurePhase(intersectionPk, phase.phaseIndex(), phase.phaseCode());
                    if (phase.roadLinkIndexes() != null) {
                        phase.roadLinkIndexes().stream()
                                .filter(Objects::nonNull)
                                .map(index -> findRoadLinkId(intersectionPk, index))
                                .flatMap(Optional::stream)
                                .forEach(roadLinkPk -> ensurePhaseRoadLink(phasePk, roadLinkPk));
                    }
                });
    }

    private UUID ensureScene(String sceneId) {
        String sceneCode = blankToDefault(sceneId, "default");
        return findSceneId(sceneCode).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into scene (
                        id, scene_code, name, source_type, cityflow_roadnet_path, cityflow_flow_path,
                        map_provider, coordinate_system
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    sceneCode,
                    sceneCode,
                    "cityflow",
                    sceneCode,
                    sceneCode,
                    "cityflow",
                    "cityflow"
            );
            return id;
        });
    }

    private UUID ensureIntersection(UUID scenePk, String cityflowId, double x, double y, boolean virtual) {
        String id = blankToDefault(cityflowId, "unknown_intersection");
        return findIntersectionId(scenePk, id).orElseGet(() -> {
            UUID pk = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into intersection (
                        id, scene_id, cityflow_id, map_intersection_id, name, type, virtual,
                        longitude, latitude, x, y
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    pk,
                    scenePk,
                    id,
                    null,
                    id,
                    "cityflow",
                    virtual,
                    null,
                    null,
                    x,
                    y
            );
            return pk;
        });
    }

    private UUID ensureRoad(UUID scenePk, RoadDto road, UUID fromIntersectionPk, UUID toIntersectionPk) {
        String roadId = blankToDefault(road.id(), "unknown_road");
        return findRoadId(scenePk, roadId).orElseGet(() -> {
            UUID pk = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into road (
                        id, scene_id, cityflow_id, from_intersection_id, to_intersection_id,
                        name, direction, length_m, speed_limit, lane_count, geometry
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    pk,
                    scenePk,
                    roadId,
                    fromIntersectionPk,
                    toIntersectionPk,
                    roadId,
                    "unknown",
                    roadLength(road.points()),
                    null,
                    road.laneCount(),
                    toJson(road.points())
            );
            return pk;
        });
    }

    private UUID ensureLane(UUID roadPk, String roadId, int laneIndex) {
        return findLaneId(roadPk, laneIndex).orElseGet(() -> {
            UUID pk = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into lane (
                        id, road_id, cityflow_lane_index, lane_code, direction, movement, width, speed_limit
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    pk,
                    roadPk,
                    laneIndex,
                    blankToDefault(roadId, "road") + "_" + laneIndex,
                    "unknown",
                    "unknown",
                    null,
                    null
            );
            return pk;
        });
    }

    private UUID ensureRoadLink(UUID intersectionPk, RoadLinkDto roadLink, UUID fromRoadPk, UUID toRoadPk) {
        return findRoadLinkId(intersectionPk, roadLink.index()).orElseGet(() -> {
            UUID pk = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into road_link (
                        id, intersection_id, cityflow_index, from_road_id, to_road_id, movement_type
                    )
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    pk,
                    intersectionPk,
                    roadLink.index(),
                    fromRoadPk,
                    toRoadPk,
                    blankToDefault(roadLink.type(), "unknown")
            );
            return pk;
        });
    }

    private UUID ensurePhase(UUID intersectionPk, int phaseIndex, String phaseCode) {
        return findPhaseId(intersectionPk, phaseIndex).orElseGet(() -> {
            UUID pk = UUID.randomUUID();
            String code = phaseCode == null || phaseCode.isBlank() ? "phase_" + phaseIndex : phaseCode;
            jdbcTemplate.update("""
                    insert into signal_phase (
                        id, intersection_id, phase_index, phase_code, phase_name, phase_type,
                        default_green_sec, yellow_sec, all_red_sec
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    pk,
                    intersectionPk,
                    phaseIndex,
                    code,
                    code,
                    "cityflow",
                    DEFAULT_PHASE_GREEN_SEC,
                    DEFAULT_PHASE_YELLOW_SEC,
                    DEFAULT_PHASE_ALL_RED_SEC
            );
            return pk;
        });
    }

    private void ensurePhaseRoadLink(UUID phasePk, UUID roadLinkPk) {
        if (exists("""
                select count(*) from signal_phase_road_link
                where phase_id = ? and road_link_id = ?
                """, phasePk, roadLinkPk)) {
            return;
        }
        jdbcTemplate.update(
                "insert into signal_phase_road_link (phase_id, road_link_id) values (?, ?)",
                phasePk,
                roadLinkPk
        );
    }

    private UUID ensureSessionExists(SimulationRuntimeSession session, UUID scenePk) {
        return findSimulationSessionId(session.getSid()).orElseGet(() -> {
            UUID pk = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into simulation_session (id, sid, scene_id, controller_type, speed, status)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    pk,
                    session.getSid(),
                    scenePk,
                    session.getControllerType(),
                    null,
                    session.getState().name().toLowerCase()
            );
            return pk;
        });
    }

    private UUID upsertFrame(UUID sessionPk, long seq, SimFrameData frame) {
        Optional<UUID> existing = findFrameId(sessionPk, seq);
        SimulationMetricsDto metrics = frame.metrics();
        int vehicleCount = metrics == null ? safeSize(frame.vehicles()) : metrics.vehicleCount();
        int queueCount = metrics == null ? 0 : metrics.queueCount();
        double avgSpeed = metrics == null ? 0.0 : metrics.avgSpeed();
        double avgWait = metrics == null ? 0.0 : metrics.avgWait();
        int throughput = metrics == null ? 0 : metrics.throughput();
        int signalCount = safeSize(frame.signals());
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    update simulation_frame
                    set sim_time = ?, vehicle_count = ?, queue_count = ?, avg_speed = ?,
                        avg_wait = ?, throughput = ?, status = ?, signal_count = ?
                    where id = ?
                    """,
                    frame.simTime(),
                    vehicleCount,
                    queueCount,
                    avgSpeed,
                    avgWait,
                    throughput,
                    frame.status(),
                    signalCount,
                    existing.get()
            );
            return existing.get();
        }
        UUID pk = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into simulation_frame (
                    id, session_id, seq, sim_time, vehicle_count, queue_count, avg_speed,
                    avg_wait, throughput, status, signal_count
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                pk,
                sessionPk,
                seq,
                frame.simTime(),
                vehicleCount,
                queueCount,
                avgSpeed,
                avgWait,
                throughput,
                frame.status(),
                signalCount
        );
        return pk;
    }

    private void persistRoadSnapshots(UUID scenePk, UUID framePk, List<RoadStateDto> roads) {
        if (roads == null) {
            return;
        }
        for (RoadStateDto road : roads) {
            if (road == null) {
                continue;
            }
            Optional<UUID> roadPk = findRoadId(scenePk, road.id());
            if (roadPk.isEmpty() || exists("""
                    select count(*) from road_state_snapshot where frame_id = ? and road_id = ?
                    """, framePk, roadPk.get())) {
                continue;
            }
            jdbcTemplate.update("""
                    insert into road_state_snapshot (
                        id, frame_id, road_id, vehicle_count, queue_count, avg_speed, level
                    )
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    framePk,
                    roadPk.get(),
                    road.vehicleCount(),
                    road.queueCount(),
                    road.avgSpeed(),
                    blankToDefault(road.level(), "unknown")
            );
        }
    }

    private void persistMovementSnapshots(
            UUID scenePk,
            UUID framePk,
            Map<String, IntersectionLaneStateDto> laneStates
    ) {
        if (laneStates == null || laneStates.isEmpty()) {
            return;
        }
        laneStates.forEach((intersectionId, intersectionLaneState) -> {
            if (intersectionLaneState == null || intersectionLaneState.lanes() == null) {
                return;
            }
            UUID intersectionPk = ensureIntersection(scenePk, intersectionId, 0.0, 0.0, true);
            intersectionLaneState.lanes().forEach((movementCode, laneState) ->
                    persistMovementSnapshot(framePk, intersectionPk, movementCode, laneState)
            );
        });
    }

    private void persistMovementSnapshot(
            UUID framePk,
            UUID intersectionPk,
            String movementCode,
            LaneMovementStateDto laneState
    ) {
        if (laneState == null || exists("""
                select count(*) from intersection_movement_state_snapshot
                where frame_id = ? and intersection_id = ? and movement_code = ?
                """, framePk, intersectionPk, movementCode)) {
            return;
        }
        List<Integer> cells = laneState.cells() == null ? List.of() : laneState.cells();
        jdbcTemplate.update("""
                insert into intersection_movement_state_snapshot (
                    id, frame_id, intersection_id, movement_code, queue_len, vehicle_count,
                    avg_wait_time, cell_1, cell_2, cell_3, cell_4
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                framePk,
                intersectionPk,
                blankToDefault(movementCode, "unknown"),
                laneState.queueLen(),
                cells.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum(),
                laneState.avgWaitTime(),
                cell(cells, 0),
                cell(cells, 1),
                cell(cells, 2),
                cell(cells, 3)
        );
    }

    private void persistIntersectionSnapshots(
            UUID scenePk,
            UUID framePk,
            List<IntersectionStateDto> intersections,
            List<SignalStateDto> signals
    ) {
        if (intersections == null || intersections.isEmpty()) {
            return;
        }
        Map<String, SignalStateDto> signalsByIntersection = signals == null
                ? Map.of()
                : signals.stream()
                .filter(Objects::nonNull)
                .filter(signal -> signal.intersectionId() != null)
                .collect(Collectors.toMap(SignalStateDto::intersectionId, signal -> signal, (left, ignored) -> left));
        for (IntersectionStateDto intersection : intersections) {
            if (intersection == null) {
                continue;
            }
            UUID intersectionPk = ensureIntersection(scenePk, intersection.id(), 0.0, 0.0, true);
            SignalStateDto signal = signalsByIntersection.get(intersection.id());
            if (signal == null || exists("""
                    select count(*) from intersection_state_snapshot
                    where frame_id = ? and intersection_id = ?
                    """, framePk, intersectionPk)) {
                continue;
            }
            UUID phasePk = ensurePhase(intersectionPk, signal.phaseIndex(), signal.phaseCode());
            jdbcTemplate.update("""
                    insert into intersection_state_snapshot (
                        id, frame_id, intersection_id, queue_count, avg_wait, level, current_phase_id
                    )
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    framePk,
                    intersectionPk,
                    intersection.queueCount(),
                    intersection.avgWait(),
                    blankToDefault(intersection.level(), "unknown"),
                    phasePk
            );
        }
    }

    private void persistVehicleSnapshots(UUID scenePk, UUID framePk, List<VehicleStateDto> vehicles) {
        if (vehicles == null) {
            return;
        }
        for (VehicleStateDto vehicle : vehicles) {
            if (vehicle == null || vehicle.id() == null || vehicle.roadId() == null) {
                continue;
            }
            Optional<UUID> roadPk = findRoadId(scenePk, vehicle.roadId());
            if (roadPk.isEmpty() || exists("""
                    select count(*) from vehicle_state_snapshot
                    where frame_id = ? and vehicle_id = ?
                    """, framePk, vehicle.id())) {
                continue;
            }
            Optional<UUID> lanePk = findLaneId(roadPk.get(), vehicle.lane());
            jdbcTemplate.update("""
                    insert into vehicle_state_snapshot (
                        id, frame_id, vehicle_id, road_id, lane_id, x, y, angle, speed, vehicle_type
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    framePk,
                    vehicle.id(),
                    roadPk.get(),
                    lanePk.orElse(null),
                    vehicle.x(),
                    vehicle.y(),
                    vehicle.angle(),
                    vehicle.speed(),
                    "car"
            );
        }
    }

    private void persistDecisions(
            UUID scenePk,
            UUID sessionPk,
            SimFrameData frame,
            List<ControlDecision> decisions
    ) {
        if (decisions == null || decisions.isEmpty()) {
            return;
        }
        Map<String, SignalStateDto> currentSignals = frame.signals() == null
                ? Map.of()
                : frame.signals().stream()
                .filter(Objects::nonNull)
                .filter(signal -> signal.intersectionId() != null)
                .collect(Collectors.toMap(SignalStateDto::intersectionId, signal -> signal, (left, ignored) -> left));
        for (ControlDecision decision : decisions) {
            if (decision == null || decision.intersectionId() == null) {
                continue;
            }
            UUID intersectionPk = ensureIntersection(scenePk, decision.intersectionId(), 0.0, 0.0, true);
            SignalStateDto currentSignal = currentSignals.get(decision.intersectionId());
            UUID requestedPhasePk = currentSignal == null
                    ? null
                    : ensurePhase(intersectionPk, currentSignal.phaseIndex(), currentSignal.phaseCode());
            UUID finalPhasePk = ensurePhase(intersectionPk, decision.phaseIndex(), decision.phaseCode());
            UUID decisionPk = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into control_decision (
                        id, session_id, intersection_id, sim_time, controller_type, requested_phase_id,
                        final_phase_id, duration_sec, status, reason, confidence, metadata
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    decisionPk,
                    sessionPk,
                    intersectionPk,
                    frame.simTime(),
                    blankToDefault(decision.controllerType(), "unknown"),
                    requestedPhasePk,
                    finalPhasePk,
                    decision.durationSec() == null ? 0 : decision.durationSec(),
                    decisionStatus(decision),
                    blankToDefault(decision.reason(), "no reason"),
                    decision.confidence(),
                    toJson(decision.metadata())
            );
            persistDecisionTrace(decisionPk, decision);
            persistTrafficRInference(sessionPk, intersectionPk, finalPhasePk, frame, decision);
            persistFallbackEvent(sessionPk, intersectionPk, frame, decision);
        }
    }

    private void persistDecisionTrace(UUID decisionPk, ControlDecision decision) {
        jdbcTemplate.update("""
                insert into control_decision_trace (
                    id, decision_id, stage, input_payload, output_payload, message
                )
                values (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                decisionPk,
                "strategy",
                toJson(decision.metadata()),
                toJson(decision),
                decision.reason()
        );
    }

    private void persistTrafficRInference(
            UUID sessionPk,
            UUID intersectionPk,
            UUID phasePk,
            SimFrameData frame,
            ControlDecision decision
    ) {
        Map<String, Object> metadata = decision.metadata();
        if (metadata == null || !isTrafficRDecision(metadata)) {
            return;
        }
        String rawOutput = stringValue(metadata.get("rawOutput"));
        UUID logPk = UUID.randomUUID();
        boolean valid = rawOutput != null && !rawOutput.isBlank();
        jdbcTemplate.update("""
                insert into traffic_r_inference_log (
                    id, session_id, sim_time, request_payload, prompt_text, raw_output,
                    parsed_phase_code, valid, latency_ms, error_message, response_payload, status
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                logPk,
                sessionPk,
                frame.simTime(),
                toJson(metadata),
                "",
                blankToDefault(rawOutput, ""),
                blankToDefault(decision.phaseCode(), ""),
                valid,
                latencyMs(metadata.get("inferenceTimeSec")),
                valid ? null : "Traffic-R decision metadata did not include rawOutput",
                toJson(decision),
                valid ? "SUCCESS" : "INVALID"
        );
        jdbcTemplate.update("""
                insert into traffic_r_inference_result (
                    id, inference_log_id, intersection_id, phase_id, phase_code,
                    confidence, valid, reason, raw_output
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                logPk,
                intersectionPk,
                phasePk,
                decision.phaseCode(),
                decision.confidence(),
                valid,
                decision.reason(),
                rawOutput
        );
    }

    private void persistFallbackEvent(
            UUID sessionPk,
            UUID intersectionPk,
            SimFrameData frame,
            ControlDecision decision
    ) {
        Map<String, Object> metadata = decision.metadata();
        if (metadata == null) {
            return;
        }
        Object mode = metadata.get("trafficRDispatchMode");
        if (!"max-pressure-fallback".equals(mode)) {
            return;
        }
        jdbcTemplate.update("""
                insert into strategy_fallback_event (
                    id, session_id, intersection_id, from_strategy, to_strategy, reason, sim_time
                )
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                sessionPk,
                intersectionPk,
                "traffic-r",
                "max-pressure",
                decision.reason(),
                frame.simTime()
        );
    }

    private Optional<UUID> findSceneId(String sceneCode) {
        return queryUuid("select id from scene where scene_code = ?", sceneCode);
    }

    private Optional<UUID> findIntersectionId(UUID scenePk, String cityflowId) {
        return queryUuid("select id from intersection where scene_id = ? and cityflow_id = ?", scenePk, cityflowId);
    }

    private Optional<UUID> findRoadId(UUID scenePk, String cityflowId) {
        return queryUuid("select id from road where scene_id = ? and cityflow_id = ?", scenePk, cityflowId);
    }

    private Optional<UUID> findLaneId(UUID roadPk, int cityflowLaneIndex) {
        return queryUuid("select id from lane where road_id = ? and cityflow_lane_index = ?", roadPk, cityflowLaneIndex);
    }

    private Optional<UUID> findRoadLinkId(UUID intersectionPk, int cityflowIndex) {
        return queryUuid(
                "select id from road_link where intersection_id = ? and cityflow_index = ?",
                intersectionPk,
                cityflowIndex
        );
    }

    private Optional<UUID> findPhaseId(UUID intersectionPk, int phaseIndex) {
        return queryUuid(
                "select id from signal_phase where intersection_id = ? and phase_index = ?",
                intersectionPk,
                phaseIndex
        );
    }

    private Optional<UUID> findSimulationSessionId(String sid) {
        return queryUuid("select id from simulation_session where sid = ?", sid);
    }

    private Optional<UUID> findFrameId(UUID sessionPk, long seq) {
        return queryUuid("select id from simulation_frame where session_id = ? and seq = ?", sessionPk, seq);
    }

    private Optional<UUID> queryUuid(String sql, Object... args) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, UUID.class, args));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private String decisionStatus(ControlDecision decision) {
        Map<String, Object> metadata = decision.metadata();
        if (metadata == null) {
            return "generated";
        }
        if (Boolean.TRUE.equals(metadata.get("cityflowApplyPending"))) {
            return "pending";
        }
        if (Boolean.TRUE.equals(metadata.get("cityflowApplied"))) {
            return "applied";
        }
        return "generated";
    }

    private boolean isTrafficRDecision(Map<String, Object> metadata) {
        return "traffic-r".equals(metadata.get("source"))
                || "traffic-r".equals(metadata.get("trafficRDispatchMode"))
                || metadata.containsKey("rawOutput");
    }

    private String sessionConfigPayload(String sceneId, String controllerType, Double speed, Double warmupSeconds) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sceneId", sceneId);
        payload.put("controllerType", controllerType);
        payload.put("speed", speed);
        payload.put("warmupSeconds", warmupSeconds);
        return toJson(payload);
    }

    private int latencyMs(Object inferenceTimeSec) {
        if (inferenceTimeSec instanceof Number number) {
            return (int) Math.round(number.doubleValue() * 1000.0);
        }
        if (inferenceTimeSec instanceof String text) {
            try {
                return (int) Math.round(Double.parseDouble(text) * 1000.0);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int cell(List<Integer> cells, int index) {
        if (cells == null || index < 0 || index >= cells.size() || cells.get(index) == null) {
            return 0;
        }
        return cells.get(index);
    }

    private int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new HashMap<>() : value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private double roadLength(List<PointDto> points) {
        if (points == null || points.size() < 2) {
            return 0.0;
        }
        double length = 0.0;
        for (int i = 1; i < points.size(); i++) {
            PointDto previous = points.get(i - 1);
            PointDto current = points.get(i);
            if (previous == null || current == null) {
                continue;
            }
            double dx = current.x() - previous.x();
            double dy = current.y() - previous.y();
            length += Math.sqrt(dx * dx + dy * dy);
        }
        return length;
    }
}
