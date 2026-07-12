package com.traffic.agent.service;

import com.traffic.common.exception.BusinessException;
import com.traffic.roadnet.dto.IntersectionDto;
import com.traffic.roadnet.dto.RoadDto;
import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.runtime.query.RuntimeQueryDtos.EmergencyEventSummary;
import com.traffic.runtime.query.RuntimeQueryService;
import com.traffic.simulation.dto.EvEventDto;
import com.traffic.simulation.dto.EvStatusDto;
import com.traffic.simulation.dto.VehicleStateDto;
import com.traffic.simulation.state.LiveSimulationStateService;
import com.traffic.simulation.state.LiveSimulationStateService.LiveStateSnapshot;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentEmergencyToolService {

    private static final double DEFAULT_INTERSECTION_TRAVEL_SECONDS = 20.0;

    private final RuntimeQueryService runtimeQueryService;
    private final LiveSimulationStateService liveSimulationStateService;

    public AgentEmergencyToolService(
            RuntimeQueryService runtimeQueryService,
            LiveSimulationStateService liveSimulationStateService
    ) {
        this.runtimeQueryService = runtimeQueryService;
        this.liveSimulationStateService = liveSimulationStateService;
    }

    public EmergencyVehicleStatusResponse getEmergencyVehicleStatus(String sid, String vehicleId, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        List<String> warnings = new ArrayList<>();
        LiveStateSnapshot live = null;
        try {
            live = liveSimulationStateService.getLiveStateSnapshot(sid);
        } catch (RuntimeException ex) {
            warnings.add("live simulation cache unavailable: " + safe(ex.getMessage()));
        }

        List<EmergencyEventSummary> events = runtimeQueryService.getEmergencyEvents(sid, null, safeLimit);
        if (StringUtils.hasText(vehicleId)) {
            String expected = vehicleId.trim();
            events = events.stream().filter(event -> expected.equals(event.vehicleId())).toList();
        }

        List<EmergencyVehicleStatus> vehicles = new ArrayList<>();
        if (live != null) {
            List<EvStatusDto> evStatuses = live.evStatus() == null ? List.of() : live.evStatus();
            for (EvStatusDto status : evStatuses) {
                if (status == null || !matchesVehicle(vehicleId, status.evId())) {
                    continue;
                }
                VehicleStateDto position = findVehicle(live, status.evId()).orElse(null);
                EvEventDto latestEvent = findLatestEvent(live, status.evId()).orElse(null);
                double eta = Math.max(0, status.totalCount() - status.passedCount()) * DEFAULT_INTERSECTION_TRAVEL_SECONDS;
                vehicles.add(new EmergencyVehicleStatus(
                        status.evId(),
                        status.evType(),
                        status.priority(),
                        "live-frame",
                        position == null ? null : position.roadId(),
                        position == null ? null : position.lane(),
                        position == null ? null : position.x(),
                        position == null ? null : position.y(),
                        position == null ? null : position.speed(),
                        status.route(),
                        status.passedCount(),
                        status.totalCount(),
                        status.completed(),
                        status.elapsedTime(),
                        eta,
                        latestEvent == null ? "unknown" : latestEvent.status(),
                        latestEvent == null ? null : latestEvent.intersectionId(),
                        latestEvent == null ? null : latestEvent.decision()
                ));
            }
        }

        if (vehicles.isEmpty() && !events.isEmpty()) {
            warnings.add("No matching emergency vehicle was found in the latest live frame; returning database events only.");
        }
        return new EmergencyVehicleStatusResponse(
                live == null ? sid : live.sid(),
                live == null ? null : live.latestSeq(),
                live == null ? null : live.latestSimTime(),
                vehicles,
                events,
                warnings,
                Instant.now()
        );
    }

    public EmergencyDispatchDraft draftEmergencyDispatch(
            String sid,
            String startIntersection,
            String endIntersection,
            String evId,
            String evType,
            Integer priority
    ) {
        List<String> warnings = new ArrayList<>();
        LiveStateSnapshot live = null;
        try {
            live = liveSimulationStateService.getLiveStateSnapshot(sid);
        } catch (RuntimeException ex) {
            warnings.add("live simulation cache unavailable: " + safe(ex.getMessage()));
        }
        RoadnetResponse roadnet = live == null ? null : live.roadnet();
        if (roadnet == null) {
            return new EmergencyDispatchDraft(
                    "draft-only",
                    false,
                    safeSid(sid, live),
                    blankToDefault(evId, "ev-draft"),
                    blankToDefault(evType, "ambulance"),
                    priority == null ? 1 : priority,
                    startIntersection,
                    endIntersection,
                    List.of(),
                    List.of(),
                    0.0,
                    List.of("无法生成路线：实时缓存中没有 roadnet，请先创建/启动仿真或指定有效 sid。"),
                    List.of("人工确认起终点、应急车辆类型、路线和安全层可用性。"),
                    warnings,
                    Instant.now()
            );
        }

        String start = resolveIntersectionId(roadnet, startIntersection)
                .orElseThrow(() -> new BusinessException("startIntersection not found in live roadnet: " + startIntersection));
        String end = resolveIntersectionId(roadnet, endIntersection)
                .orElseThrow(() -> new BusinessException("endIntersection not found in live roadnet: " + endIntersection));
        RoutePlan route = shortestRoute(roadnet, start, end);
        List<String> recommendations = new ArrayList<>();
        if (route.intersections().isEmpty()) {
            recommendations.add("未找到从起点到终点的连通路径，建议检查 CityFlow roadnet 拓扑。");
        } else {
            recommendations.add("沿途路口只生成绿波请求草案，正式下发前必须经过安全层与人工确认。");
            recommendations.add("每个路口优先选择与应急车行进方向匹配的相位；若安全层阻断，应保持原相位或 fallback。");
            recommendations.add("应急任务结束后恢复原策略，并记录 emergency_signal_event 供复盘。");
        }
        List<String> humanConfirmation = List.of(
                "确认起终点与 CityFlow roadnet 映射正确。",
                "确认路线不会穿越不可控或 virtual intersection。",
                "确认绿波请求经过 Safety Layer，不直接覆盖黄灯/全红/最小绿灯约束。"
        );
        return new EmergencyDispatchDraft(
                "draft-only",
                !route.intersections().isEmpty(),
                safeSid(sid, live),
                blankToDefault(evId, "ev-draft"),
                blankToDefault(evType, "ambulance"),
                priority == null ? 1 : priority,
                start,
                end,
                route.intersections(),
                route.roads(),
                Math.max(0, route.roads().size()) * DEFAULT_INTERSECTION_TRAVEL_SECONDS,
                recommendations,
                humanConfirmation,
                warnings,
                Instant.now()
        );
    }

    private RoutePlan shortestRoute(RoadnetResponse roadnet, String start, String end) {
        Map<String, List<RoadEdge>> graph = new LinkedHashMap<>();
        if (roadnet.roads() != null) {
            for (RoadDto road : roadnet.roads()) {
                if (road == null || !StringUtils.hasText(road.from()) || !StringUtils.hasText(road.to())) {
                    continue;
                }
                graph.computeIfAbsent(road.from(), ignored -> new ArrayList<>())
                        .add(new RoadEdge(road.to(), road.id()));
            }
        }
        ArrayDeque<RouteNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new RouteNode(start, List.of(start), List.of()));
        visited.add(start);
        while (!queue.isEmpty()) {
            RouteNode current = queue.removeFirst();
            if (current.intersectionId().equals(end)) {
                return new RoutePlan(current.intersections(), current.roads());
            }
            for (RoadEdge edge : graph.getOrDefault(current.intersectionId(), List.of())) {
                if (visited.add(edge.toIntersectionId())) {
                    List<String> nextIntersections = new ArrayList<>(current.intersections());
                    nextIntersections.add(edge.toIntersectionId());
                    List<String> nextRoads = new ArrayList<>(current.roads());
                    nextRoads.add(edge.roadId());
                    queue.addLast(new RouteNode(edge.toIntersectionId(), nextIntersections, nextRoads));
                }
            }
        }
        return new RoutePlan(List.of(), List.of());
    }

    private Optional<String> resolveIntersectionId(RoadnetResponse roadnet, String intersectionId) {
        if (!StringUtils.hasText(intersectionId) || roadnet.intersections() == null) {
            return Optional.empty();
        }
        return roadnet.intersections().stream()
                .filter(item -> item != null && intersectionId.trim().equals(item.id()))
                .map(IntersectionDto::id)
                .findFirst();
    }

    private Optional<VehicleStateDto> findVehicle(LiveStateSnapshot live, String vehicleId) {
        if (live.vehicles() == null || !StringUtils.hasText(vehicleId)) {
            return Optional.empty();
        }
        return live.vehicles().stream()
                .filter(vehicle -> vehicle != null && vehicleId.equals(vehicle.id()))
                .findFirst();
    }

    private Optional<EvEventDto> findLatestEvent(LiveStateSnapshot live, String vehicleId) {
        if (live.evEvents() == null || !StringUtils.hasText(vehicleId)) {
            return Optional.empty();
        }
        return live.evEvents().stream()
                .filter(event -> event != null && vehicleId.equals(event.evId()))
                .max(Comparator.comparingDouble(EvEventDto::timestamp));
    }

    private boolean matchesVehicle(String expected, String actual) {
        return !StringUtils.hasText(expected) || expected.trim().equals(actual);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private String blankToDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String safeSid(String sid, LiveStateSnapshot live) {
        if (live != null && StringUtils.hasText(live.sid())) {
            return live.sid();
        }
        return sid;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record EmergencyVehicleStatusResponse(
            String sid,
            Long latestSeq,
            Double latestSimTime,
            List<EmergencyVehicleStatus> vehicles,
            List<EmergencyEventSummary> databaseEvents,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record EmergencyVehicleStatus(
            String evId,
            String evType,
            int priority,
            String dataSource,
            String roadId,
            Integer lane,
            Double x,
            Double y,
            Double speed,
            List<String> route,
            int passedCount,
            int totalCount,
            boolean completed,
            double elapsedTime,
            double estimatedRemainingSeconds,
            String greenWaveStatus,
            String latestIntersectionId,
            String latestGreenWaveDecision
    ) {
    }

    public record EmergencyDispatchDraft(
            String status,
            boolean routeFound,
            String sid,
            String evId,
            String evType,
            int priority,
            String startIntersection,
            String endIntersection,
            List<String> routeIntersections,
            List<String> routeRoads,
            double estimatedTravelSeconds,
            List<String> recommendations,
            List<String> humanConfirmationRequired,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    private record RouteNode(String intersectionId, List<String> intersections, List<String> roads) {
    }

    private record RoadEdge(String toIntersectionId, String roadId) {
    }

    private record RoutePlan(List<String> intersections, List<String> roads) {
    }
}
