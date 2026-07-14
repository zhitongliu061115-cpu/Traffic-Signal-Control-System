package com.traffic.runtime.query;

import com.traffic.common.exception.BusinessException;
import com.traffic.common.util.TrafficDisplayNames;
import com.traffic.runtime.query.RuntimeQueryDtos.ControlDecisionSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.CurrentSimulationState;
import com.traffic.runtime.query.RuntimeQueryDtos.DecisionTraceEntry;
import com.traffic.runtime.query.RuntimeQueryDtos.DecisionTraceResponse;
import com.traffic.runtime.query.RuntimeQueryDtos.DecisionEffectSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.AlertEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.EmergencyEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.FallbackEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.FrameSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.InferenceResultSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.IntersectionDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.LaneInfo;
import com.traffic.runtime.query.RuntimeQueryDtos.ModelInferenceLogSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.MaxPressureScoreSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.MovementSnapshot;
import com.traffic.runtime.query.RuntimeQueryDtos.PhaseInfo;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadLinkInfo;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadSnapshot;
import com.traffic.runtime.query.RuntimeQueryDtos.SafetyEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.ServiceHealthItem;
import com.traffic.runtime.query.RuntimeQueryDtos.SessionSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.SignalSnapshot;
import com.traffic.runtime.query.RuntimeQueryDtos.SystemHealthResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RuntimeQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RuntimeQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CurrentSimulationState getCurrentSimulationState(String sid) {
        SessionSummary session = findSession(sid)
                .orElseThrow(() -> new BusinessException("未找到仿真会话：" + blankToLatest(sid)));
        FrameSummary latestFrame = findLatestFrame(session.id()).orElse(null);
        long frameCount = countFrames(session.id());
        List<SignalSnapshot> signals = latestFrame == null
                ? List.of()
                : queryLatestSignals(session.id(), latestFrame.id(), MAX_LIMIT);
        return new CurrentSimulationState(session, latestFrame, frameCount, signals);
    }

    public IntersectionDetail getIntersectionDetail(String intersectionId, String sid, String sceneCode) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("identifier", intersectionId)
                .addValue("sceneCode", sceneCode);
        StringBuilder sql = new StringBuilder("""
                select i.id, s.scene_code, i.cityflow_id, i.map_intersection_id, i.name, i.type,
                       i.virtual, i.longitude, i.latitude, i.x, i.y
                from intersection i
                join scene s on s.id = i.scene_id
                where (i.cityflow_id = :identifier or i.map_intersection_id = :identifier
                """);
        Optional<UUID> uuid = parseUuid(intersectionId);
        uuid.ifPresent(value -> {
            sql.append(" or i.id = :uuid");
            params.addValue("uuid", value);
        });
        sql.append(")");
        if (hasText(sceneCode)) {
            sql.append(" and s.scene_code = :sceneCode");
        }
        sql.append(" order by i.virtual, i.cityflow_id limit 1");

        IntersectionRow row = jdbcTemplate.query(sql.toString(), params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return mapIntersectionRow(rs);
        });
        if (row == null) {
            throw new BusinessException("未找到路口：" + intersectionId);
        }

        SignalSnapshot latestState = findLatestIntersectionState(row.id(), sid).orElse(null);
        List<MovementSnapshot> movements = latestState == null
                ? List.of()
                : queryLatestMovements(row.id(), sid, MAX_LIMIT);
        return new IntersectionDetail(
                row.id(),
                row.sceneCode(),
                row.cityflowId(),
                row.mapIntersectionId(),
                hasText(row.name()) ? row.name() : TrafficDisplayNames.intersectionName(row.sceneCode(), row.cityflowId()),
                row.type(),
                row.virtualIntersection(),
                row.longitude(),
                row.latitude(),
                row.x(),
                row.y(),
                latestState,
                movements,
                queryPhases(row.id()),
                queryRoadLinks(row.id())
        );
    }

    public RoadDetail getRoadDetail(String roadId, String sid, String sceneCode) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("identifier", roadId)
                .addValue("sceneCode", sceneCode);
        StringBuilder sql = new StringBuilder("""
                select r.id, s.scene_code, r.cityflow_id, fi.cityflow_id as from_intersection_id,
                       ti.cityflow_id as to_intersection_id, r.name, r.direction, r.length_m,
                       r.speed_limit, r.lane_count, r.geometry
                from road r
                join scene s on s.id = r.scene_id
                join intersection fi on fi.id = r.from_intersection_id
                join intersection ti on ti.id = r.to_intersection_id
                where (r.cityflow_id = :identifier
                """);
        Optional<UUID> uuid = parseUuid(roadId);
        uuid.ifPresent(value -> {
            sql.append(" or r.id = :uuid");
            params.addValue("uuid", value);
        });
        sql.append(")");
        if (hasText(sceneCode)) {
            sql.append(" and s.scene_code = :sceneCode");
        }
        sql.append(" order by r.cityflow_id limit 1");

        RoadRow row = jdbcTemplate.query(sql.toString(), params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return mapRoadRow(rs);
        });
        if (row == null) {
            throw new BusinessException("未找到道路：" + roadId);
        }

        return new RoadDetail(
                row.id(),
                row.sceneCode(),
                row.cityflowId(),
                row.fromIntersectionId(),
                row.toIntersectionId(),
                hasText(row.name())
                        ? row.name()
                        : TrafficDisplayNames.roadName(row.sceneCode(), row.cityflowId(), row.fromIntersectionId(), row.toIntersectionId()),
                row.direction(),
                row.lengthM(),
                row.speedLimit(),
                row.laneCount(),
                row.geometry(),
                findLatestRoadSnapshot(row.id(), sid).orElse(null),
                queryLanes(row.id())
        );
    }

    public List<ControlDecisionSummary> getLatestControlDecisions(
            String sid,
            String intersectionId,
            int requestedLimit
    ) {
        int limit = normalizeLimit(requestedLimit);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder sql = new StringBuilder(controlDecisionSelectSql());
        sql.append(" where 1 = 1");
        appendSidFilter(sql, params, sid, "ss");
        appendIntersectionFilter(sql, params, intersectionId, "i");
        sql.append(" order by cd.created_at desc, cd.sim_time desc limit :limit");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> mapControlDecision(rs));
    }

    public DecisionTraceResponse getDecisionTrace(String decisionId) {
        UUID decisionUuid = parseUuid(decisionId)
                .orElseThrow(() -> new BusinessException("decisionId 必须是 UUID：" + decisionId));
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("decisionId", decisionUuid);
        String decisionSql = controlDecisionSelectSql() + " where cd.id = :decisionId";
        ControlDecisionSummary decision = jdbcTemplate.query(decisionSql, params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return mapControlDecision(rs);
        });
        if (decision == null) {
            throw new BusinessException("未找到控制决策：" + decisionId);
        }

        List<DecisionTraceEntry> traces = jdbcTemplate.query("""
                select id, stage, input_payload, output_payload, message, created_at
                from control_decision_trace
                where decision_id = :decisionId
                order by created_at, stage
                """, params, (rs, rowNum) -> new DecisionTraceEntry(
                uuidString(rs, "id"),
                rs.getString("stage"),
                rs.getString("input_payload"),
                rs.getString("output_payload"),
                rs.getString("message"),
                instant(rs, "created_at")
        ));
        List<MaxPressureScoreSummary> maxPressureScores = jdbcTemplate.query("""
                select mps.id, sp.id as phase_id, sp.phase_index, sp.phase_code,
                       mps.pressure_score, mps.detail_payload, mps.created_at
                from max_pressure_score mps
                join signal_phase sp on sp.id = mps.phase_id
                where mps.decision_id = :decisionId
                order by mps.pressure_score desc, sp.phase_index
                """, params, (rs, rowNum) -> new MaxPressureScoreSummary(
                uuidString(rs, "id"),
                uuidString(rs, "phase_id"),
                rs.getInt("phase_index"),
                rs.getString("phase_code"),
                rs.getDouble("pressure_score"),
                rs.getString("detail_payload"),
                instant(rs, "created_at")
        ));
        DecisionEffectSummary effect = jdbcTemplate.query("""
                select id, before_frame_id, after_frame_id, horizon_sec,
                       queue_before, queue_after, queue_delta,
                       avg_wait_before, avg_wait_after, avg_wait_delta,
                       avg_speed_before, avg_speed_after, avg_speed_delta,
                       throughput_before, throughput_after, throughput_delta,
                       evaluation_label, detail_payload, created_at
                from control_decision_effect
                where decision_id = :decisionId
                """, params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new DecisionEffectSummary(
                    uuidString(rs, "id"),
                    uuidString(rs, "before_frame_id"),
                    uuidString(rs, "after_frame_id"),
                    rs.getInt("horizon_sec"),
                    rs.getInt("queue_before"),
                    rs.getInt("queue_after"),
                    rs.getInt("queue_delta"),
                    rs.getDouble("avg_wait_before"),
                    rs.getDouble("avg_wait_after"),
                    rs.getDouble("avg_wait_delta"),
                    rs.getDouble("avg_speed_before"),
                    rs.getDouble("avg_speed_after"),
                    rs.getDouble("avg_speed_delta"),
                    rs.getInt("throughput_before"),
                    rs.getInt("throughput_after"),
                    rs.getInt("throughput_delta"),
                    rs.getString("evaluation_label"),
                    rs.getString("detail_payload"),
                    instant(rs, "created_at")
            );
        });
        return new DecisionTraceResponse(decision, traces, maxPressureScores, effect);
    }

    public List<ModelInferenceLogSummary> getModelInferenceLog(
            String sid,
            String intersectionId,
            int requestedLimit
    ) {
        int limit = normalizeLimit(requestedLimit);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                select l.id, ss.sid, l.sim_time, l.request_id, l.model_name, l.request_payload,
                       l.prompt_text, l.raw_output, l.response_payload, l.parsed_phase_code,
                       l.valid, l.latency_ms, l.status, l.error_message, l.created_at
                from traffic_r_inference_log l
                join simulation_session ss on ss.id = l.session_id
                where 1 = 1
                """);
        appendSidFilter(sql, params, sid, "ss");
        if (hasText(intersectionId)) {
            sql.append("""
                    and exists (
                        select 1
                        from traffic_r_inference_result r
                        join intersection i on i.id = r.intersection_id
                        where r.inference_log_id = l.id
                    """);
            appendIntersectionFilter(sql, params, intersectionId, "i");
            sql.append(")");
        }
        sql.append(" order by l.created_at desc, l.sim_time desc limit :limit");

        List<ModelInferenceLogSummary> logs = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) ->
                new ModelInferenceLogSummary(
                        uuidString(rs, "id"),
                        rs.getString("sid"),
                        rs.getDouble("sim_time"),
                        rs.getString("request_id"),
                        rs.getString("model_name"),
                        rs.getString("request_payload"),
                        rs.getString("prompt_text"),
                        rs.getString("raw_output"),
                        rs.getString("response_payload"),
                        rs.getString("parsed_phase_code"),
                        rs.getBoolean("valid"),
                        rs.getInt("latency_ms"),
                        rs.getString("status"),
                        rs.getString("error_message"),
                        instant(rs, "created_at"),
                        List.of()
                ));
        if (logs.isEmpty()) {
            return logs;
        }

        List<UUID> logIds = logs.stream()
                .map(log -> UUID.fromString(log.id()))
                .toList();
        Map<String, List<InferenceResultSummary>> resultsByLogId = queryInferenceResults(logIds).stream()
                .collect(Collectors.groupingBy(ResultWithLogId::logId, LinkedHashMap::new,
                        Collectors.mapping(ResultWithLogId::result, Collectors.toList())));

        return logs.stream()
                .map(log -> new ModelInferenceLogSummary(
                        log.id(),
                        log.sid(),
                        log.simTime(),
                        log.requestId(),
                        log.modelName(),
                        log.requestPayload(),
                        log.promptText(),
                        log.rawOutput(),
                        log.responsePayload(),
                        log.parsedPhaseCode(),
                        log.valid(),
                        log.latencyMs(),
                        log.status(),
                        log.errorMessage(),
                        log.createdAt(),
                        resultsByLogId.getOrDefault(log.id(), List.of())
                ))
                .toList();
    }

    public SystemHealthResponse getSystemHealth(int requestedLimit) {
        int limit = normalizeLimit(requestedLimit);
        Map<String, Long> tableCounts = new LinkedHashMap<>();
        for (String table : List.of(
                "simulation_session",
                "simulation_frame",
                "control_decision",
                "control_decision_effect",
                "max_pressure_score",
                "traffic_r_inference_log",
                "strategy_fallback_event",
                "safety_constraint_event",
                "alert_event"
        )) {
            tableCounts.put(table, countTable(table));
        }

        Map<String, Long> sessionStatusCounts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select status, count(*) as count
                from simulation_session
                group by status
                order by status
                """, new MapSqlParameterSource(), rs -> {
            sessionStatusCounts.put(rs.getString("status"), rs.getLong("count"));
        });

        List<ServiceHealthItem> services = jdbcTemplate.query("""
                select id, service_name, status, latency_ms, detail_payload, checked_at
                from service_health_snapshot
                order by checked_at desc
                limit :limit
                """, new MapSqlParameterSource("limit", limit), (rs, rowNum) -> new ServiceHealthItem(
                uuidString(rs, "id"),
                rs.getString("service_name"),
                rs.getString("status"),
                rs.getInt("latency_ms"),
                rs.getString("detail_payload"),
                instant(rs, "checked_at")
        ));

        return new SystemHealthResponse(true, tableCounts, sessionStatusCounts, services);
    }

    public List<FallbackEventSummary> getFallbackEvents(String sid, String intersectionId, int requestedLimit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", normalizeLimit(requestedLimit));
        StringBuilder sql = new StringBuilder("""
                select fe.id, ss.sid, i.id as intersection_id, i.cityflow_id,
                       fe.from_strategy, fe.to_strategy, fe.reason, fe.sim_time, fe.created_at
                from strategy_fallback_event fe
                join simulation_session ss on ss.id = fe.session_id
                join intersection i on i.id = fe.intersection_id
                where 1 = 1
                """);
        appendSidFilter(sql, params, sid, "ss");
        appendIntersectionFilter(sql, params, intersectionId, "i");
        sql.append(" order by fe.created_at desc, fe.sim_time desc limit :limit");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new FallbackEventSummary(
                uuidString(rs, "id"),
                rs.getString("sid"),
                uuidString(rs, "intersection_id"),
                rs.getString("cityflow_id"),
                rs.getString("from_strategy"),
                rs.getString("to_strategy"),
                rs.getString("reason"),
                rs.getDouble("sim_time"),
                instant(rs, "created_at")
        ));
    }

    public List<SafetyEventSummary> getSafetyEvents(
            String sid,
            String intersectionId,
            String decisionId,
            int requestedLimit
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", normalizeLimit(requestedLimit));
        StringBuilder sql = new StringBuilder("""
                select se.id, cd.id as decision_id, ss.sid, i.id as intersection_id, i.cityflow_id,
                       se.constraint_type, se.action,
                       bp.id as before_phase_id, bp.phase_code as before_phase_code,
                       ap.id as after_phase_id, ap.phase_code as after_phase_code,
                       se.reason, se.created_at
                from safety_constraint_event se
                join control_decision cd on cd.id = se.decision_id
                join simulation_session ss on ss.id = cd.session_id
                join intersection i on i.id = cd.intersection_id
                left join signal_phase bp on bp.id = se.before_phase_id
                left join signal_phase ap on ap.id = se.after_phase_id
                where 1 = 1
                """);
        appendSidFilter(sql, params, sid, "ss");
        appendIntersectionFilter(sql, params, intersectionId, "i");
        if (hasText(decisionId)) {
            UUID uuid = parseUuid(decisionId)
                    .orElseThrow(() -> new BusinessException("decisionId 必须是 UUID：" + decisionId));
            sql.append(" and cd.id = :decisionId");
            params.addValue("decisionId", uuid);
        }
        sql.append(" order by se.created_at desc limit :limit");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new SafetyEventSummary(
                uuidString(rs, "id"),
                uuidString(rs, "decision_id"),
                rs.getString("sid"),
                uuidString(rs, "intersection_id"),
                rs.getString("cityflow_id"),
                rs.getString("constraint_type"),
                rs.getString("action"),
                uuidString(rs, "before_phase_id"),
                rs.getString("before_phase_code"),
                uuidString(rs, "after_phase_id"),
                rs.getString("after_phase_code"),
                rs.getString("reason"),
                instant(rs, "created_at")
        ));
    }

    public List<AlertEventSummary> getAlertEvents(String sid, String level, String status, int requestedLimit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", normalizeLimit(requestedLimit))
                .addValue("level", level)
                .addValue("status", status);
        StringBuilder sql = new StringBuilder("""
                select ae.id, ss.sid, ae.alert_type, ae.level, ae.target_type, ae.target_id,
                       ae.title, ae.description, ae.status, ae.created_at, ae.updated_at
                from alert_event ae
                left join simulation_session ss on ss.id = ae.session_id
                where 1 = 1
                """);
        appendSidFilter(sql, params, sid, "ss");
        if (hasText(level)) {
            sql.append(" and ae.level = :level");
        }
        if (hasText(status)) {
            sql.append(" and ae.status = :status");
        }
        sql.append(" order by ae.created_at desc limit :limit");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new AlertEventSummary(
                uuidString(rs, "id"),
                rs.getString("sid"),
                rs.getString("alert_type"),
                rs.getString("level"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        ));
    }

    public List<EmergencyEventSummary> getEmergencyEvents(String sid, String status, int requestedLimit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", normalizeLimit(requestedLimit))
                .addValue("status", status);
        StringBuilder sql = new StringBuilder("""
                select ee.id, ss.sid, ee.event_code, ee.vehicle_id, ee.vehicle_type, ee.priority,
                       ee.status, ee.start_coord, ee.end_coord, ee.created_at, ee.updated_at,
                       ee.ended_at, ee.error_message
                from emergency_event ee
                join simulation_session ss on ss.id = ee.session_id
                where 1 = 1
                """);
        appendSidFilter(sql, params, sid, "ss");
        if (hasText(status)) {
            sql.append(" and ee.status = :status");
        }
        sql.append(" order by ee.created_at desc limit :limit");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new EmergencyEventSummary(
                uuidString(rs, "id"),
                rs.getString("sid"),
                rs.getString("event_code"),
                rs.getString("vehicle_id"),
                rs.getString("vehicle_type"),
                rs.getInt("priority"),
                rs.getString("status"),
                rs.getString("start_coord"),
                rs.getString("end_coord"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "ended_at"),
                rs.getString("error_message")
        ));
    }

    private Optional<SessionSummary> findSession(String sid) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql;
        if (hasText(sid)) {
            sql = """
                    select ss.id, ss.sid, sc.scene_code, ss.controller_type, ss.speed, ss.warmup_seconds,
                           ss.status, ss.created_at, ss.started_at, ss.ended_at, ss.updated_at
                    from simulation_session ss
                    join scene sc on sc.id = ss.scene_id
                    where ss.sid = :sid
                    limit 1
                    """;
            params.addValue("sid", sid);
        } else {
            sql = """
                    select ss.id, ss.sid, sc.scene_code, ss.controller_type, ss.speed, ss.warmup_seconds,
                           ss.status, ss.created_at, ss.started_at, ss.ended_at, ss.updated_at
                    from simulation_session ss
                    join scene sc on sc.id = ss.scene_id
                    order by coalesce(ss.started_at, ss.created_at) desc
                    limit 1
                    """;
        }
        return Optional.ofNullable(jdbcTemplate.query(sql, params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return mapSession(rs);
        }));
    }

    private Optional<FrameSummary> findLatestFrame(String sessionId) {
        MapSqlParameterSource params = new MapSqlParameterSource("sessionId", UUID.fromString(sessionId));
        return Optional.ofNullable(jdbcTemplate.query("""
                select id, seq, sim_time, vehicle_count, queue_count, avg_speed, avg_wait,
                       throughput, status, signal_count, captured_at
                from simulation_frame
                where session_id = :sessionId
                order by seq desc
                limit 1
                """, params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return mapFrame(rs);
        }));
    }

    private long countFrames(String sessionId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from simulation_frame where session_id = :sessionId",
                new MapSqlParameterSource("sessionId", UUID.fromString(sessionId)),
                Long.class
        );
        return count == null ? 0L : count;
    }

    private List<SignalSnapshot> queryLatestSignals(String sessionId, String frameId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", UUID.fromString(sessionId))
                .addValue("frameId", UUID.fromString(frameId))
                .addValue("limit", limit);
        return jdbcTemplate.query("""
                select i.id as intersection_id, s.scene_code, i.cityflow_id, sp.phase_index, sp.phase_code,
                       iss.queue_count, iss.avg_wait, iss.level
                from intersection_state_snapshot iss
                join simulation_frame sf on sf.id = iss.frame_id
                join intersection i on i.id = iss.intersection_id
                join scene s on s.id = i.scene_id
                join signal_phase sp on sp.id = iss.current_phase_id
                where sf.session_id = :sessionId and sf.id = :frameId
                order by i.cityflow_id
                limit :limit
                """, params, (rs, rowNum) -> mapSignalSnapshot(rs));
    }

    private Optional<SignalSnapshot> findLatestIntersectionState(String intersectionUuid, String sid) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("intersectionId", UUID.fromString(intersectionUuid));
        StringBuilder sql = new StringBuilder("""
                select i.id as intersection_id, s.scene_code, i.cityflow_id, sp.phase_index, sp.phase_code,
                       iss.queue_count, iss.avg_wait, iss.level
                from intersection_state_snapshot iss
                join simulation_frame sf on sf.id = iss.frame_id
                join simulation_session ss on ss.id = sf.session_id
                join intersection i on i.id = iss.intersection_id
                join scene s on s.id = i.scene_id
                join signal_phase sp on sp.id = iss.current_phase_id
                where iss.intersection_id = :intersectionId
                """);
        appendSidFilter(sql, params, sid, "ss");
        sql.append(" order by sf.seq desc limit 1");
        return Optional.ofNullable(jdbcTemplate.query(sql.toString(), params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return mapSignalSnapshot(rs);
        }));
    }

    private List<MovementSnapshot> queryLatestMovements(String intersectionUuid, String sid, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("intersectionId", UUID.fromString(intersectionUuid))
                .addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                select im.movement_code, im.queue_len, im.vehicle_count, im.avg_wait_time,
                       im.avg_speed, im.cell_1, im.cell_2, im.cell_3, im.cell_4,
                       sf.sim_time, sf.seq
                from intersection_movement_state_snapshot im
                join simulation_frame sf on sf.id = im.frame_id
                join simulation_session ss on ss.id = sf.session_id
                where im.intersection_id = :intersectionId
                """);
        appendSidFilter(sql, params, sid, "ss");
        sql.append("""
                and sf.id = (
                    select sf2.id
                    from intersection_movement_state_snapshot im2
                    join simulation_frame sf2 on sf2.id = im2.frame_id
                    join simulation_session ss2 on ss2.id = sf2.session_id
                    where im2.intersection_id = :intersectionId
                """);
        appendSidFilter(sql, params, sid, "ss2");
        sql.append("""
                    order by sf2.seq desc
                    limit 1
                )
                order by im.movement_code
                limit :limit
                """);
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new MovementSnapshot(
                rs.getString("movement_code"),
                rs.getInt("queue_len"),
                rs.getInt("vehicle_count"),
                rs.getDouble("avg_wait_time"),
                nullableDouble(rs, "avg_speed"),
                List.of(rs.getInt("cell_1"), rs.getInt("cell_2"), rs.getInt("cell_3"), rs.getInt("cell_4")),
                rs.getDouble("sim_time"),
                rs.getLong("seq")
        ));
    }

    private List<PhaseInfo> queryPhases(String intersectionUuid) {
        return jdbcTemplate.query("""
                select id, phase_index, phase_code, phase_name, phase_type,
                       default_green_sec, yellow_sec, all_red_sec
                from signal_phase
                where intersection_id = :intersectionId
                order by phase_index
                """, new MapSqlParameterSource("intersectionId", UUID.fromString(intersectionUuid)), (rs, rowNum) ->
                new PhaseInfo(
                        uuidString(rs, "id"),
                        rs.getInt("phase_index"),
                        rs.getString("phase_code"),
                        rs.getString("phase_name"),
                        rs.getString("phase_type"),
                        rs.getInt("default_green_sec"),
                        rs.getInt("yellow_sec"),
                        rs.getInt("all_red_sec")
                ));
    }

    private List<RoadLinkInfo> queryRoadLinks(String intersectionUuid) {
        return jdbcTemplate.query("""
                select rl.id, rl.cityflow_index, fr.cityflow_id as from_road_id,
                       tr.cityflow_id as to_road_id, rl.movement_type
                from road_link rl
                join road fr on fr.id = rl.from_road_id
                join road tr on tr.id = rl.to_road_id
                where rl.intersection_id = :intersectionId
                order by rl.cityflow_index
                """, new MapSqlParameterSource("intersectionId", UUID.fromString(intersectionUuid)), (rs, rowNum) ->
                new RoadLinkInfo(
                        uuidString(rs, "id"),
                        rs.getInt("cityflow_index"),
                        rs.getString("from_road_id"),
                        rs.getString("to_road_id"),
                        rs.getString("movement_type")
                ));
    }

    private Optional<RoadSnapshot> findLatestRoadSnapshot(String roadUuid, String sid) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("roadId", UUID.fromString(roadUuid));
        StringBuilder sql = new StringBuilder("""
                select rs.vehicle_count, rs.queue_count, rs.avg_speed, rs.level, sf.sim_time, sf.seq
                from road_state_snapshot rs
                join simulation_frame sf on sf.id = rs.frame_id
                join simulation_session ss on ss.id = sf.session_id
                where rs.road_id = :roadId
                """);
        appendSidFilter(sql, params, sid, "ss");
        sql.append(" order by sf.seq desc limit 1");
        return Optional.ofNullable(jdbcTemplate.query(sql.toString(), params, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new RoadSnapshot(
                    rs.getInt("vehicle_count"),
                    rs.getInt("queue_count"),
                    rs.getDouble("avg_speed"),
                    rs.getString("level"),
                    rs.getDouble("sim_time"),
                    rs.getLong("seq")
            );
        }));
    }

    private List<LaneInfo> queryLanes(String roadUuid) {
        return jdbcTemplate.query("""
                select id, cityflow_lane_index, lane_code, direction, movement, width, speed_limit
                from lane
                where road_id = :roadId
                order by cityflow_lane_index
                """, new MapSqlParameterSource("roadId", UUID.fromString(roadUuid)), (rs, rowNum) -> new LaneInfo(
                uuidString(rs, "id"),
                rs.getInt("cityflow_lane_index"),
                rs.getString("lane_code"),
                rs.getString("direction"),
                rs.getString("movement"),
                nullableDouble(rs, "width"),
                nullableDouble(rs, "speed_limit")
        ));
    }

    private List<ResultWithLogId> queryInferenceResults(List<UUID> logIds) {
        return jdbcTemplate.query("""
                select r.inference_log_id, r.id, i.id as intersection_id, i.cityflow_id,
                       r.phase_id, r.phase_code, r.confidence, r.valid, r.reason,
                       r.raw_output, r.created_at
                from traffic_r_inference_result r
                join intersection i on i.id = r.intersection_id
                where r.inference_log_id in (:logIds)
                order by r.created_at, i.cityflow_id
                """, new MapSqlParameterSource("logIds", logIds), (rs, rowNum) -> new ResultWithLogId(
                uuidString(rs, "inference_log_id"),
                new InferenceResultSummary(
                        uuidString(rs, "id"),
                        uuidString(rs, "intersection_id"),
                        rs.getString("cityflow_id"),
                        uuidString(rs, "phase_id"),
                        rs.getString("phase_code"),
                        nullableDouble(rs, "confidence"),
                        rs.getBoolean("valid"),
                        rs.getString("reason"),
                        rs.getString("raw_output"),
                        instant(rs, "created_at")
                )
        ));
    }

    private long countTable(String tableName) {
        try {
            Long count = jdbcTemplate.queryForObject("select count(*) from " + tableName,
                    new MapSqlParameterSource(), Long.class);
            return count == null ? 0L : count;
        } catch (DataAccessException ex) {
            return -1L;
        }
    }

    private String controlDecisionSelectSql() {
        return """
                select cd.id, ss.sid, i.id as intersection_id, i.cityflow_id,
                       cd.sim_time, cd.controller_type,
                       rp.id as requested_phase_id, rp.phase_code as requested_phase_code,
                       fp.id as final_phase_id, fp.phase_code as final_phase_code,
                       cd.duration_sec, cd.status, cd.reason, cd.confidence, cd.metadata,
                       cd.error_message, cd.created_at, cd.updated_at
                from control_decision cd
                join simulation_session ss on ss.id = cd.session_id
                join intersection i on i.id = cd.intersection_id
                left join signal_phase rp on rp.id = cd.requested_phase_id
                join signal_phase fp on fp.id = cd.final_phase_id
                """;
    }

    private void appendSidFilter(StringBuilder sql, MapSqlParameterSource params, String sid, String sessionAlias) {
        if (!hasText(sid)) {
            return;
        }
        sql.append(" and ").append(sessionAlias).append(".sid = :sid ");
        params.addValue("sid", sid);
    }

    private void appendIntersectionFilter(
            StringBuilder sql,
            MapSqlParameterSource params,
            String intersectionId,
            String intersectionAlias
    ) {
        if (!hasText(intersectionId)) {
            return;
        }
        sql.append(" and (")
                .append(intersectionAlias).append(".cityflow_id = :intersectionId or ")
                .append(intersectionAlias).append(".map_intersection_id = :intersectionId");
        params.addValue("intersectionId", intersectionId);
        parseUuid(intersectionId).ifPresent(uuid -> {
            sql.append(" or ").append(intersectionAlias).append(".id = :intersectionUuid");
            params.addValue("intersectionUuid", uuid);
        });
        sql.append(") ");
    }

    private SessionSummary mapSession(ResultSet rs) throws SQLException {
        return new SessionSummary(
                uuidString(rs, "id"),
                rs.getString("sid"),
                rs.getString("scene_code"),
                rs.getString("controller_type"),
                nullableDouble(rs, "speed"),
                nullableDouble(rs, "warmup_seconds"),
                rs.getString("status"),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "ended_at"),
                instant(rs, "updated_at")
        );
    }

    private FrameSummary mapFrame(ResultSet rs) throws SQLException {
        return new FrameSummary(
                uuidString(rs, "id"),
                rs.getLong("seq"),
                rs.getDouble("sim_time"),
                rs.getInt("vehicle_count"),
                rs.getInt("queue_count"),
                rs.getDouble("avg_speed"),
                rs.getDouble("avg_wait"),
                rs.getInt("throughput"),
                rs.getString("status"),
                rs.getInt("signal_count"),
                instant(rs, "captured_at")
        );
    }

    private SignalSnapshot mapSignalSnapshot(ResultSet rs) throws SQLException {
        return new SignalSnapshot(
                uuidString(rs, "intersection_id"),
                rs.getString("cityflow_id"),
                TrafficDisplayNames.intersectionName(rs.getString("scene_code"), rs.getString("cityflow_id")),
                nullableInteger(rs, "phase_index"),
                rs.getString("phase_code"),
                rs.getInt("queue_count"),
                rs.getDouble("avg_wait"),
                rs.getString("level")
        );
    }

    private ControlDecisionSummary mapControlDecision(ResultSet rs) throws SQLException {
        return new ControlDecisionSummary(
                uuidString(rs, "id"),
                rs.getString("sid"),
                uuidString(rs, "intersection_id"),
                rs.getString("cityflow_id"),
                rs.getDouble("sim_time"),
                rs.getString("controller_type"),
                uuidString(rs, "requested_phase_id"),
                rs.getString("requested_phase_code"),
                uuidString(rs, "final_phase_id"),
                rs.getString("final_phase_code"),
                rs.getInt("duration_sec"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getDouble("confidence"),
                rs.getString("metadata"),
                rs.getString("error_message"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private IntersectionRow mapIntersectionRow(ResultSet rs) throws SQLException {
        return new IntersectionRow(
                uuidString(rs, "id"),
                rs.getString("scene_code"),
                rs.getString("cityflow_id"),
                rs.getString("map_intersection_id"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getBoolean("virtual"),
                nullableDouble(rs, "longitude"),
                nullableDouble(rs, "latitude"),
                rs.getDouble("x"),
                rs.getDouble("y")
        );
    }

    private RoadRow mapRoadRow(ResultSet rs) throws SQLException {
        return new RoadRow(
                uuidString(rs, "id"),
                rs.getString("scene_code"),
                rs.getString("cityflow_id"),
                rs.getString("from_intersection_id"),
                rs.getString("to_intersection_id"),
                rs.getString("name"),
                rs.getString("direction"),
                rs.getDouble("length_m"),
                nullableDouble(rs, "speed_limit"),
                rs.getInt("lane_count"),
                rs.getString("geometry")
        );
    }

    private Optional<UUID> parseUuid(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToLatest(String sid) {
        return hasText(sid) ? sid : "latest";
    }

    private String uuidString(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : value.toString().toLowerCase(Locale.ROOT);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private record IntersectionRow(
            String id,
            String sceneCode,
            String cityflowId,
            String mapIntersectionId,
            String name,
            String type,
            boolean virtualIntersection,
            Double longitude,
            Double latitude,
            double x,
            double y
    ) {
    }

    private record RoadRow(
            String id,
            String sceneCode,
            String cityflowId,
            String fromIntersectionId,
            String toIntersectionId,
            String name,
            String direction,
            double lengthM,
            Double speedLimit,
            int laneCount,
            String geometry
    ) {
    }

    private record ResultWithLogId(String logId, InferenceResultSummary result) {
    }
}
