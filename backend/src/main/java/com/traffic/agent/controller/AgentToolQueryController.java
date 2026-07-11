package com.traffic.agent.controller;

import com.traffic.agent.service.AgentDataService;
import com.traffic.common.response.ApiResponse;
import com.traffic.runtime.query.RuntimeQueryDtos.AlertEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.ControlDecisionSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.CurrentSimulationState;
import com.traffic.runtime.query.RuntimeQueryDtos.DecisionTraceResponse;
import com.traffic.runtime.query.RuntimeQueryDtos.EmergencyEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.FallbackEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.IntersectionDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.ModelInferenceLogSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.SafetyEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.SystemHealthResponse;
import com.traffic.runtime.query.RuntimeQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/tools")
public class AgentToolQueryController {

    private final RuntimeQueryService runtimeQueryService;
    private final AgentDataService agentDataService;

    public AgentToolQueryController(RuntimeQueryService runtimeQueryService, AgentDataService agentDataService) {
        this.runtimeQueryService = runtimeQueryService;
        this.agentDataService = agentDataService;
    }

    @GetMapping("/get_current_simulation_state")
    public ApiResponse<CurrentSimulationState> getCurrentSimulationState(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_current_simulation_state",
                args("sid", sid),
                () -> runtimeQueryService.getCurrentSimulationState(sid)
        );
    }

    @GetMapping("/get_intersection_detail/{intersectionId}")
    public ApiResponse<IntersectionDetail> getIntersectionDetail(
            @PathVariable String intersectionId,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String sceneCode,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_intersection_detail",
                args("intersectionId", intersectionId, "sid", sid, "sceneCode", sceneCode),
                () -> runtimeQueryService.getIntersectionDetail(intersectionId, sid, sceneCode)
        );
    }

    @GetMapping("/get_road_detail/{roadId}")
    public ApiResponse<RoadDetail> getRoadDetail(
            @PathVariable String roadId,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String sceneCode,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_road_detail",
                args("roadId", roadId, "sid", sid, "sceneCode", sceneCode),
                () -> runtimeQueryService.getRoadDetail(roadId, sid, sceneCode)
        );
    }

    @GetMapping("/get_latest_control_decisions")
    public ApiResponse<List<ControlDecisionSummary>> getLatestControlDecisions(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String intersectionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_latest_control_decisions",
                args("sid", sid, "intersectionId", intersectionId, "limit", limit),
                () -> runtimeQueryService.getLatestControlDecisions(sid, intersectionId, limit)
        );
    }

    @GetMapping("/get_decision_trace/{decisionId}")
    public ApiResponse<DecisionTraceResponse> getDecisionTrace(
            @PathVariable String decisionId,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_decision_trace",
                args("decisionId", decisionId),
                () -> runtimeQueryService.getDecisionTrace(decisionId)
        );
    }

    @GetMapping("/get_system_health")
    public ApiResponse<SystemHealthResponse> getSystemHealth(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_system_health",
                args("limit", limit),
                () -> runtimeQueryService.getSystemHealth(limit)
        );
    }

    @GetMapping("/get_model_inference_log")
    public ApiResponse<List<ModelInferenceLogSummary>> getModelInferenceLog(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String intersectionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_model_inference_log",
                args("sid", sid, "intersectionId", intersectionId, "limit", limit),
                () -> runtimeQueryService.getModelInferenceLog(sid, intersectionId, limit)
        );
    }

    @GetMapping("/get_fallback_events")
    public ApiResponse<List<FallbackEventSummary>> getFallbackEvents(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String intersectionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_fallback_events",
                args("sid", sid, "intersectionId", intersectionId, "limit", limit),
                () -> runtimeQueryService.getFallbackEvents(sid, intersectionId, limit)
        );
    }

    @GetMapping("/get_safety_events")
    public ApiResponse<List<SafetyEventSummary>> getSafetyEvents(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String intersectionId,
            @RequestParam(required = false) String decisionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_safety_events",
                args("sid", sid, "intersectionId", intersectionId, "decisionId", decisionId, "limit", limit),
                () -> runtimeQueryService.getSafetyEvents(sid, intersectionId, decisionId, limit)
        );
    }

    @GetMapping("/get_alert_events")
    public ApiResponse<List<AlertEventSummary>> getAlertEvents(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_alert_events",
                args("sid", sid, "level", level, "status", status, "limit", limit),
                () -> runtimeQueryService.getAlertEvents(sid, level, status, limit)
        );
    }

    @GetMapping("/get_emergency_events")
    public ApiResponse<List<EmergencyEventSummary>> getEmergencyEvents(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String messageId
    ) {
        return runTool(
                messageId,
                "get_emergency_events",
                args("sid", sid, "status", status, "limit", limit),
                () -> runtimeQueryService.getEmergencyEvents(sid, status, limit)
        );
    }

    private <T> ApiResponse<T> runTool(
            String messageId,
            String toolName,
            Map<String, Object> arguments,
            Supplier<T> supplier
    ) {
        long startNanos = System.nanoTime();
        try {
            T result = supplier.get();
            recordToolCallIfRequested(messageId, toolName, arguments, result, "SUCCESS", startNanos, null);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            recordToolCallIfRequested(messageId, toolName, arguments, Map.of(), "FAILED", startNanos, ex.getMessage());
            throw ex;
        }
    }

    private void recordToolCallIfRequested(
            String messageId,
            String toolName,
            Map<String, Object> arguments,
            Object result,
            String status,
            long startNanos,
            String errorMessage
    ) {
        if (!hasText(messageId)) {
            return;
        }
        int latencyMs = (int) Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        agentDataService.recordToolCall(messageId, toolName, arguments, result, status, latencyMs, errorMessage);
    }

    private Map<String, Object> args(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key != null) {
                values.put(String.valueOf(key), keyValues[i + 1]);
            }
        }
        return values;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
