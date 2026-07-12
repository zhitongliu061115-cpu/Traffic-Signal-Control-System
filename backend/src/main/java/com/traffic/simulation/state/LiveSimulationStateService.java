package com.traffic.simulation.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.common.exception.BusinessException;
import com.traffic.roadnet.dto.IntersectionDto;
import com.traffic.roadnet.dto.PhaseDto;
import com.traffic.roadnet.dto.RoadDto;
import com.traffic.roadnet.dto.RoadLinkDto;
import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.runtime.query.RuntimeQueryDtos.CurrentSimulationState;
import com.traffic.runtime.query.RuntimeQueryDtos.FrameSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.IntersectionDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.LaneInfo;
import com.traffic.runtime.query.RuntimeQueryDtos.MovementSnapshot;
import com.traffic.runtime.query.RuntimeQueryDtos.PhaseInfo;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadLinkInfo;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadSnapshot;
import com.traffic.runtime.query.RuntimeQueryDtos.SessionSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.SignalSnapshot;
import com.traffic.simulation.dto.IntersectionLaneStateDto;
import com.traffic.simulation.dto.IntersectionStateDto;
import com.traffic.simulation.dto.LaneMovementStateDto;
import com.traffic.simulation.dto.RoadStateDto;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SimulationMetricsDto;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.strategy.dto.ControlDecision;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LiveSimulationStateService {

    private static final int DEFAULT_FRAME_WINDOW = 5;
    private static final int MAX_SESSION_CACHE = 20;
    private static final int DEFAULT_PHASE_GREEN_SEC = 10;
    private static final int DEFAULT_PHASE_YELLOW_SEC = 3;
    private static final int DEFAULT_PHASE_ALL_RED_SEC = 1;

    private final ObjectMapper objectMapper;
    private final Map<String, LiveSessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, RoadnetResponse> roadnetsByScene = new ConcurrentHashMap<>();

    public LiveSimulationStateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized void registerSession(String sid, String sceneId, String controllerType, String status, RoadnetResponse roadnet) {
        if (!StringUtils.hasText(sid)) {
            return;
        }
        if (roadnet != null && StringUtils.hasText(roadnet.sceneId())) {
            roadnetsByScene.put(roadnet.sceneId(), roadnet);
        }
        sessions.put(sid, new LiveSessionState(
                sid,
                blankToDefault(sceneId, roadnet == null ? "default" : roadnet.sceneId()),
                blankToDefault(controllerType, "unknown"),
                blankToDefault(status, "created"),
                Instant.now()
        ));
        pruneOldSessions();
    }

    public synchronized void updateSessionStatus(String sid, String status) {
        LiveSessionState state = sessions.get(sid);
        if (state != null) {
            state.status = blankToDefault(status, state.status);
            state.updatedAt = Instant.now();
        }
    }

    public synchronized void removeSession(String sid) {
        if (StringUtils.hasText(sid)) {
            sessions.remove(sid);
        }
    }

    public synchronized void recordFrame(
            SimulationRuntimeSession session,
            long seq,
            SimFrameData frame,
            List<ControlDecision> decisions
    ) {
        if (session == null || frame == null || !StringUtils.hasText(session.getSid())) {
            return;
        }
        LiveSessionState state = sessions.computeIfAbsent(session.getSid(), sid -> new LiveSessionState(
                sid,
                session.getSceneId(),
                session.getControllerType(),
                session.getState().name().toLowerCase(),
                Instant.now()
        ));
        state.sceneId = session.getSceneId();
        state.controllerType = session.getControllerType();
        state.status = session.getState().name().toLowerCase();
        state.updatedAt = Instant.now();
        state.frames.addLast(new LiveFrame(seq, frame, decisions == null ? List.of() : List.copyOf(decisions), Instant.now()));
        while (state.frames.size() > DEFAULT_FRAME_WINDOW) {
            state.frames.removeFirst();
        }
    }

    public synchronized CurrentSimulationState getCurrentSimulationState(String sid) {
        LiveSessionState state = resolveSession(sid);
        LiveFrame latest = latestFrame(state).orElse(null);
        return new CurrentSimulationState(
                mapSession(state),
                latest == null ? null : mapFrame(latest),
                state.frames.size(),
                latest == null ? List.of() : mapSignals(latest.frame())
        );
    }

    public synchronized IntersectionDetail getIntersectionDetail(String intersectionId, String sid, String sceneCode) {
        if (!StringUtils.hasText(intersectionId)) {
            throw new BusinessException("intersectionId 不能为空");
        }
        LiveSessionState state = resolveSessionBySidOrScene(sid, sceneCode);
        RoadnetResponse roadnet = roadnet(state);
        IntersectionDto intersection = findIntersection(roadnet, intersectionId).orElse(null);
        LiveFrame latest = latestFrame(state).orElse(null);
        String cityflowId = intersection == null ? intersectionId : intersection.id();
        SignalSnapshot latestState = latest == null ? null : mapSignal(latest.frame(), cityflowId).orElse(null);

        if (intersection == null && latestState == null && !hasLaneState(latest == null ? null : latest.frame(), cityflowId)) {
            throw new BusinessException("实时缓存中未找到路口：" + intersectionId);
        }

        return new IntersectionDetail(
                cityflowId,
                state.sceneId,
                cityflowId,
                null,
                cityflowId,
                "cityflow-live",
                intersection == null || intersection.virtual(),
                null,
                null,
                intersection == null ? 0.0 : intersection.x(),
                intersection == null ? 0.0 : intersection.y(),
                latestState,
                latest == null ? List.of() : mapMovements(latest.frame(), latest.seq(), cityflowId),
                mapPhases(roadnet, cityflowId),
                mapRoadLinks(roadnet, cityflowId)
        );
    }

    public synchronized RoadDetail getRoadDetail(String roadId, String sid, String sceneCode) {
        if (!StringUtils.hasText(roadId)) {
            throw new BusinessException("roadId 不能为空");
        }
        LiveSessionState state = resolveSessionBySidOrScene(sid, sceneCode);
        RoadnetResponse roadnet = roadnet(state);
        RoadDto road = findRoad(roadnet, roadId).orElse(null);
        LiveFrame latest = latestFrame(state).orElse(null);
        RoadStateDto roadState = latest == null ? null : findRoadState(latest.frame(), roadId).orElse(null);
        if (road == null && roadState == null) {
            throw new BusinessException("实时缓存中未找到道路：" + roadId);
        }
        String cityflowId = road == null ? roadId : road.id();
        int laneCount = road == null ? 0 : road.laneCount();
        return new RoadDetail(
                cityflowId,
                state.sceneId,
                cityflowId,
                road == null ? null : road.from(),
                road == null ? null : road.to(),
                cityflowId,
                "unknown",
                road == null ? 0.0 : roadLength(road),
                null,
                laneCount,
                road == null ? "[]" : toJson(road.points()),
                roadState == null ? null : new RoadSnapshot(
                        roadState.vehicleCount(),
                        roadState.queueCount(),
                        roadState.avgSpeed(),
                        roadState.level(),
                        latest.frame().simTime(),
                        latest.seq()
                ),
                mapLanes(cityflowId, laneCount)
        );
    }

    private LiveSessionState resolveSession(String sid) {
        if (StringUtils.hasText(sid)) {
            LiveSessionState state = sessions.get(sid);
            if (state == null) {
                throw new BusinessException("实时仿真状态不存在或已释放：" + sid);
            }
            return state;
        }
        return sessions.values().stream()
                .max(Comparator.comparing(LiveSessionState::sortTime))
                .orElseThrow(() -> new BusinessException("当前没有可用的实时仿真状态"));
    }

    private LiveSessionState resolveSessionBySidOrScene(String sid, String sceneCode) {
        if (StringUtils.hasText(sid)) {
            return resolveSession(sid);
        }
        return sessions.values().stream()
                .filter(state -> !StringUtils.hasText(sceneCode) || sceneCode.equals(state.sceneId))
                .max(Comparator.comparing(LiveSessionState::sortTime))
                .orElseThrow(() -> new BusinessException("当前没有可用的实时仿真状态"));
    }

    private Optional<LiveFrame> latestFrame(LiveSessionState state) {
        return Optional.ofNullable(state.frames.peekLast());
    }

    private RoadnetResponse roadnet(LiveSessionState state) {
        return roadnetsByScene.get(state.sceneId);
    }

    private SessionSummary mapSession(LiveSessionState state) {
        return new SessionSummary(
                state.sid,
                state.sid,
                state.sceneId,
                state.controllerType,
                null,
                null,
                state.status,
                state.createdAt,
                null,
                null,
                state.updatedAt
        );
    }

    private FrameSummary mapFrame(LiveFrame liveFrame) {
        SimFrameData frame = liveFrame.frame();
        SimulationMetricsDto metrics = frame.metrics();
        int vehicleCount = metrics == null ? safeSize(frame.vehicles()) : metrics.vehicleCount();
        int queueCount = metrics == null ? 0 : metrics.queueCount();
        double avgSpeed = metrics == null ? 0.0 : metrics.avgSpeed();
        double avgWait = metrics == null ? 0.0 : metrics.avgWait();
        int throughput = metrics == null ? 0 : metrics.throughput();
        return new FrameSummary(
                "live:" + liveFrame.seq(),
                liveFrame.seq(),
                frame.simTime(),
                vehicleCount,
                queueCount,
                avgSpeed,
                avgWait,
                throughput,
                frame.status(),
                safeSize(frame.signals()),
                liveFrame.capturedAt()
        );
    }

    private List<SignalSnapshot> mapSignals(SimFrameData frame) {
        Map<String, IntersectionStateDto> intersections = indexIntersections(frame);
        if (frame.signals() == null) {
            return List.of();
        }
        return frame.signals().stream()
                .filter(signal -> signal != null && StringUtils.hasText(signal.intersectionId()))
                .map(signal -> mapSignal(signal, intersections.get(signal.intersectionId())))
                .toList();
    }

    private Optional<SignalSnapshot> mapSignal(SimFrameData frame, String intersectionId) {
        if (frame.signals() == null) {
            return Optional.empty();
        }
        Map<String, IntersectionStateDto> intersections = indexIntersections(frame);
        return frame.signals().stream()
                .filter(signal -> signal != null && intersectionId.equals(signal.intersectionId()))
                .findFirst()
                .map(signal -> mapSignal(signal, intersections.get(intersectionId)));
    }

    private SignalSnapshot mapSignal(SignalStateDto signal, IntersectionStateDto state) {
        return new SignalSnapshot(
                signal.intersectionId(),
                signal.intersectionId(),
                signal.phaseIndex(),
                signal.phaseCode(),
                state == null ? 0 : state.queueCount(),
                state == null ? 0.0 : state.avgWait(),
                state == null ? "unknown" : state.level()
        );
    }

    private Map<String, IntersectionStateDto> indexIntersections(SimFrameData frame) {
        Map<String, IntersectionStateDto> values = new LinkedHashMap<>();
        if (frame.intersections() != null) {
            for (IntersectionStateDto item : frame.intersections()) {
                if (item != null && StringUtils.hasText(item.id())) {
                    values.put(item.id(), item);
                }
            }
        }
        return values;
    }

    private List<MovementSnapshot> mapMovements(SimFrameData frame, long frameSeq, String intersectionId) {
        if (frame.laneStates() == null) {
            return List.of();
        }
        IntersectionLaneStateDto state = frame.laneStates().get(intersectionId);
        if (state == null || state.lanes() == null) {
            return List.of();
        }
        return state.lanes().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .map(entry -> {
                    LaneMovementStateDto lane = entry.getValue();
                    List<Integer> cells = lane.cells() == null ? List.of() : lane.cells();
                    return new MovementSnapshot(
                            entry.getKey(),
                            lane.queueLen(),
                            cells.stream().filter(value -> value != null).mapToInt(Integer::intValue).sum(),
                            lane.avgWaitTime(),
                            null,
                            normalizeCells(cells),
                            frame.simTime(),
                            frameSeq
                    );
                })
                .toList();
    }

    private boolean hasLaneState(SimFrameData frame, String intersectionId) {
        return frame != null && frame.laneStates() != null && frame.laneStates().containsKey(intersectionId);
    }

    private List<Integer> normalizeCells(List<Integer> cells) {
        List<Integer> normalized = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            normalized.add(i < cells.size() && cells.get(i) != null ? cells.get(i) : 0);
        }
        return normalized;
    }

    private List<PhaseInfo> mapPhases(RoadnetResponse roadnet, String intersectionId) {
        if (roadnet == null || roadnet.phases() == null) {
            return List.of();
        }
        return roadnet.phases().stream()
                .filter(phase -> phase != null && intersectionId.equals(phase.intersectionId()))
                .map(phase -> new PhaseInfo(
                        phaseId(phase),
                        phase.phaseIndex(),
                        phase.phaseCode(),
                        phase.phaseCode(),
                        "cityflow-live",
                        DEFAULT_PHASE_GREEN_SEC,
                        DEFAULT_PHASE_YELLOW_SEC,
                        DEFAULT_PHASE_ALL_RED_SEC
                ))
                .toList();
    }

    private List<RoadLinkInfo> mapRoadLinks(RoadnetResponse roadnet, String intersectionId) {
        if (roadnet == null || roadnet.roadLinks() == null) {
            return List.of();
        }
        return roadnet.roadLinks().stream()
                .filter(link -> link != null && intersectionId.equals(link.intersectionId()))
                .map(link -> new RoadLinkInfo(
                        link.intersectionId() + ":" + link.index(),
                        link.index(),
                        link.fromRoadId(),
                        link.toRoadId(),
                        link.type()
                ))
                .toList();
    }

    private Optional<IntersectionDto> findIntersection(RoadnetResponse roadnet, String intersectionId) {
        if (roadnet == null || roadnet.intersections() == null) {
            return Optional.empty();
        }
        return roadnet.intersections().stream()
                .filter(intersection -> intersection != null && intersectionId.equals(intersection.id()))
                .findFirst();
    }

    private Optional<RoadDto> findRoad(RoadnetResponse roadnet, String roadId) {
        if (roadnet == null || roadnet.roads() == null) {
            return Optional.empty();
        }
        return roadnet.roads().stream()
                .filter(road -> road != null && roadId.equals(road.id()))
                .findFirst();
    }

    private Optional<RoadStateDto> findRoadState(SimFrameData frame, String roadId) {
        if (frame.roads() == null) {
            return Optional.empty();
        }
        return frame.roads().stream()
                .filter(road -> road != null && roadId.equals(road.id()))
                .findFirst();
    }

    private List<LaneInfo> mapLanes(String roadId, int laneCount) {
        List<LaneInfo> lanes = new ArrayList<>();
        for (int i = 0; i < laneCount; i++) {
            lanes.add(new LaneInfo(
                    roadId + "_" + i,
                    i,
                    roadId + "_" + i,
                    "unknown",
                    "unknown",
                    null,
                    null
            ));
        }
        return lanes;
    }

    private String phaseId(PhaseDto phase) {
        return phase.intersectionId() + ":" + phase.phaseIndex();
    }

    private double roadLength(RoadDto road) {
        if (road.points() == null || road.points().size() < 2) {
            return 0.0;
        }
        double length = 0.0;
        for (int i = 1; i < road.points().size(); i++) {
            var previous = road.points().get(i - 1);
            var current = road.points().get(i);
            if (previous == null || current == null) {
                continue;
            }
            double dx = current.x() - previous.x();
            double dy = current.y() - previous.y();
            length += Math.sqrt(dx * dx + dy * dy);
        }
        return length;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private String blankToDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private void pruneOldSessions() {
        while (sessions.size() > MAX_SESSION_CACHE) {
            sessions.values().stream()
                    .min(Comparator.comparing(LiveSessionState::sortTime))
                    .map(state -> state.sid)
                    .ifPresentOrElse(sessions::remove, () -> {
                    });
        }
    }

    private static final class LiveSessionState {
        private final String sid;
        private String sceneId;
        private String controllerType;
        private String status;
        private final Instant createdAt;
        private Instant updatedAt;
        private final Deque<LiveFrame> frames = new ArrayDeque<>();

        private LiveSessionState(String sid, String sceneId, String controllerType, String status, Instant now) {
            this.sid = sid;
            this.sceneId = sceneId;
            this.controllerType = controllerType;
            this.status = status;
            this.createdAt = now;
            this.updatedAt = now;
        }

        private Instant sortTime() {
            return updatedAt == null ? createdAt : updatedAt;
        }
    }

    private record LiveFrame(
            long seq,
            SimFrameData frame,
            List<ControlDecision> decisions,
            Instant capturedAt
    ) {
    }
}
