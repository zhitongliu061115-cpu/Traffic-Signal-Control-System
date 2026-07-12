package com.traffic.runtime.query;

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
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runtime")
public class RuntimeQueryController {

    private final RuntimeQueryService runtimeQueryService;

    public RuntimeQueryController(RuntimeQueryService runtimeQueryService) {
        this.runtimeQueryService = runtimeQueryService;
    }

    @GetMapping("/simulations/current")
    public ApiResponse<CurrentSimulationState> getCurrentSimulationState(
            @RequestParam(required = false) String sid
    ) {
        return ApiResponse.ok(runtimeQueryService.getCurrentSimulationState(sid));
    }

    @GetMapping("/intersections/{intersectionId}")
    public ApiResponse<IntersectionDetail> getIntersectionDetail(
            @PathVariable String intersectionId,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String sceneCode
    ) {
        return ApiResponse.ok(runtimeQueryService.getIntersectionDetail(intersectionId, sid, sceneCode));
    }

    @GetMapping("/roads/{roadId}")
    public ApiResponse<RoadDetail> getRoadDetail(
            @PathVariable String roadId,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String sceneCode
    ) {
        return ApiResponse.ok(runtimeQueryService.getRoadDetail(roadId, sid, sceneCode));
    }

    @GetMapping("/control-decisions")
    public ApiResponse<List<ControlDecisionSummary>> getLatestControlDecisions(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String intersectionId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(runtimeQueryService.getLatestControlDecisions(sid, intersectionId, limit));
    }

    @GetMapping("/control-decisions/{decisionId}/trace")
    public ApiResponse<DecisionTraceResponse> getDecisionTrace(@PathVariable String decisionId) {
        return ApiResponse.ok(runtimeQueryService.getDecisionTrace(decisionId));
    }

    @GetMapping("/system-health")
    public ApiResponse<SystemHealthResponse> getSystemHealth(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(runtimeQueryService.getSystemHealth(limit));
    }

    @GetMapping("/model-inferences")
    public ApiResponse<List<ModelInferenceLogSummary>> getModelInferenceLog(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String intersectionId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(runtimeQueryService.getModelInferenceLog(sid, intersectionId, limit));
    }

    @GetMapping("/fallback-events")
    public ApiResponse<List<FallbackEventSummary>> getFallbackEvents(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String intersectionId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(runtimeQueryService.getFallbackEvents(sid, intersectionId, limit));
    }

    @GetMapping("/safety-events")
    public ApiResponse<List<SafetyEventSummary>> getSafetyEvents(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String intersectionId,
            @RequestParam(required = false) String decisionId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(runtimeQueryService.getSafetyEvents(sid, intersectionId, decisionId, limit));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<AlertEventSummary>> getAlertEvents(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(runtimeQueryService.getAlertEvents(sid, level, status, limit));
    }

    @GetMapping("/emergency-events")
    public ApiResponse<List<EmergencyEventSummary>> getEmergencyEvents(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(runtimeQueryService.getEmergencyEvents(sid, status, limit));
    }
}
