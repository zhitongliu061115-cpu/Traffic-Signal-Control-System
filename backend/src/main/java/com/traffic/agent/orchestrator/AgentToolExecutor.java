package com.traffic.agent.orchestrator;

import com.traffic.agent.dto.AgentDataDtos.ToolCallResponse;
import com.traffic.agent.service.AgentDataService;
import com.traffic.agent.tool.AgentToolResult;
import com.traffic.agent.tool.EmergencyAgentTools;
import com.traffic.agent.tool.TrafficDecisionAgentTools;
import com.traffic.agent.tool.TrafficDiagnosisAgentTools;
import com.traffic.agent.tool.TrafficHealthAgentTools;
import com.traffic.agent.tool.TrafficKnowledgeAgentTools;
import com.traffic.agent.tool.TrafficRuntimeAgentTools;
import com.traffic.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentToolExecutor {

    private static final int DEFAULT_LIMIT = 20;
    private static final List<String> ALLOWED_TOOLS = List.of(
            "get_current_simulation_state",
            "get_intersection_detail",
            "get_road_detail",
            "get_latest_control_decisions",
            "get_decision_trace",
            "get_system_health",
            "get_model_inference_log",
            "search_knowledge_base",
            "diagnose_congestion",
            "detect_signal_anomaly",
            "detect_spillback_risk",
            "get_safety_constraint_log",
            "get_fallback_log",
            "get_region_metrics",
            "compare_strategy_metrics",
            "get_fallback_events",
            "get_safety_events",
            "get_alert_events",
            "get_emergency_events"
    );

    private final AgentDataService agentDataService;
    private final TrafficRuntimeAgentTools runtimeTools;
    private final TrafficDecisionAgentTools decisionTools;
    private final TrafficHealthAgentTools healthTools;
    private final TrafficKnowledgeAgentTools knowledgeTools;
    private final TrafficDiagnosisAgentTools diagnosisTools;
    private final EmergencyAgentTools emergencyTools;
    private final AgentDebugLogService debugLogService;

    public AgentToolExecutor(
            AgentDataService agentDataService,
            TrafficRuntimeAgentTools runtimeTools,
            TrafficDecisionAgentTools decisionTools,
            TrafficHealthAgentTools healthTools,
            TrafficKnowledgeAgentTools knowledgeTools,
            TrafficDiagnosisAgentTools diagnosisTools,
            EmergencyAgentTools emergencyTools
    ) {
        this(
                agentDataService,
                runtimeTools,
                decisionTools,
                healthTools,
                knowledgeTools,
                diagnosisTools,
                emergencyTools,
                new AgentDebugLogService(new ObjectMapper())
        );
    }

    @Autowired
    public AgentToolExecutor(
            AgentDataService agentDataService,
            TrafficRuntimeAgentTools runtimeTools,
            TrafficDecisionAgentTools decisionTools,
            TrafficHealthAgentTools healthTools,
            TrafficKnowledgeAgentTools knowledgeTools,
            TrafficDiagnosisAgentTools diagnosisTools,
            EmergencyAgentTools emergencyTools,
            AgentDebugLogService debugLogService
    ) {
        this.agentDataService = agentDataService;
        this.runtimeTools = runtimeTools;
        this.decisionTools = decisionTools;
        this.healthTools = healthTools;
        this.knowledgeTools = knowledgeTools;
        this.diagnosisTools = diagnosisTools;
        this.emergencyTools = emergencyTools;
        this.debugLogService = debugLogService;
    }

    public List<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    public AgentToolExecution execute(String messageId, AgentPlan.PlannedToolCall plannedCall) {
        String toolName = normalizeToolName(plannedCall.toolName());
        Map<String, Object> arguments = normalizeArguments(plannedCall.arguments());
        long startNanos = System.nanoTime();
        debugLogService.info("agent.tool.start", Map.of(
                "messageId", messageId,
                "toolName", toolName,
                "arguments", arguments,
                "reason", plannedCall.reason() == null ? "" : plannedCall.reason()
        ));
        try {
            AgentToolResult result = callTool(toolName, arguments);
            int latencyMs = elapsedMs(startNanos);
            String status = result.success() ? "SUCCESS" : "FAILED";
            String errorMessage = result.success() ? null : String.join("; ", result.warnings());
            debugLogService.info("agent.tool.result", Map.of(
                    "messageId", messageId,
                    "toolName", toolName,
                    "arguments", arguments,
                    "status", status,
                    "latencyMs", latencyMs,
                    "result", result
            ));
            ToolCallResponse audit = agentDataService.recordToolCall(
                    messageId,
                    toolName,
                    arguments,
                    result,
                    status,
                    latencyMs,
                    errorMessage
            );
            return new AgentToolExecution(audit.id(), toolName, arguments, result, status, latencyMs, errorMessage);
        } catch (RuntimeException ex) {
            int latencyMs = elapsedMs(startNanos);
            debugLogService.error("agent.tool.error", Map.of(
                    "messageId", messageId,
                    "toolName", toolName,
                    "arguments", arguments,
                    "latencyMs", latencyMs,
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ), ex);
            ToolCallResponse audit = agentDataService.recordToolCall(
                    messageId,
                    toolName,
                    arguments,
                    Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
                    "FAILED",
                    latencyMs,
                    ex.getMessage()
            );
            return new AgentToolExecution(audit.id(), toolName, arguments, null, "FAILED", latencyMs, ex.getMessage());
        }
    }

    private AgentToolResult callTool(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "get_current_simulation_state" ->
                    runtimeTools.getCurrentSimulationState(stringArg(arguments, "sid", false));
            case "get_intersection_detail" -> runtimeTools.getIntersectionDetail(
                    stringArg(arguments, "intersectionId", true),
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "sceneCode", false)
            );
            case "get_road_detail" -> runtimeTools.getRoadDetail(
                    stringArg(arguments, "roadId", true),
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "sceneCode", false)
            );
            case "get_latest_control_decisions" -> decisionTools.getLatestControlDecisions(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "intersectionId", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "get_decision_trace" ->
                    decisionTools.getDecisionTrace(stringArg(arguments, "decisionId", true));
            case "get_system_health" ->
                    healthTools.getSystemHealth(intArg(arguments, "limit", DEFAULT_LIMIT));
            case "get_model_inference_log" -> decisionTools.getModelInferenceLog(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "intersectionId", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "search_knowledge_base" -> knowledgeTools.searchKnowledgeBase(
                    stringArg(arguments, "query", true),
                    intArg(arguments, "topK", 5),
                    stringArg(arguments, "scope", false)
            );
            case "diagnose_congestion" -> diagnosisTools.diagnoseCongestion(
                    stringArg(arguments, "targetType", false),
                    stringArg(arguments, "targetId", false),
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "sceneCode", false)
            );
            case "detect_signal_anomaly" -> diagnosisTools.detectSignalAnomaly(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "intersectionId", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "detect_spillback_risk" -> diagnosisTools.detectSpillbackRisk(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "roadId", false),
                    stringArg(arguments, "intersectionId", false),
                    stringArg(arguments, "sceneCode", false)
            );
            case "get_safety_constraint_log" -> diagnosisTools.getSafetyConstraintLog(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "intersectionId", false),
                    stringArg(arguments, "decisionId", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "get_fallback_log" -> diagnosisTools.getFallbackLog(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "intersectionId", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "get_region_metrics" -> diagnosisTools.getRegionMetrics(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "regionId", false),
                    stringArg(arguments, "intersectionIds", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "compare_strategy_metrics" -> diagnosisTools.compareStrategyMetrics(
                    stringArg(arguments, "sids", false),
                    stringArg(arguments, "sceneCode", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "get_fallback_events" -> diagnosisTools.getFallbackEvents(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "intersectionId", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "get_safety_events" -> diagnosisTools.getSafetyEvents(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "intersectionId", false),
                    stringArg(arguments, "decisionId", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "get_alert_events" -> diagnosisTools.getAlertEvents(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "level", false),
                    stringArg(arguments, "status", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            case "get_emergency_events" -> emergencyTools.getEmergencyEvents(
                    stringArg(arguments, "sid", false),
                    stringArg(arguments, "status", false),
                    intArg(arguments, "limit", DEFAULT_LIMIT)
            );
            default -> throw new BusinessException("不允许的 Agent 工具：" + toolName);
        };
    }

    private String normalizeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new BusinessException("toolName 不能为空");
        }
        String normalized = toolName.trim();
        if (!ALLOWED_TOOLS.contains(normalized)) {
            throw new BusinessException("不允许的 Agent 工具：" + normalized);
        }
        return normalized;
    }

    private Map<String, Object> normalizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        });
        return normalized;
    }

    private String stringArg(Map<String, Object> arguments, String name, boolean required) {
        Object value = arguments.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            if (required) {
                throw new BusinessException("工具参数缺失：" + name);
            }
            return null;
        }
        return String.valueOf(value).trim();
    }

    private int intArg(Map<String, Object> arguments, String name, int defaultValue) {
        Object value = arguments.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            if (parsed <= 0) {
                return defaultValue;
            }
            return Math.min(parsed, 100);
        } catch (NumberFormatException ex) {
            throw new BusinessException("工具参数必须是整数：" + name);
        }
    }

    private int elapsedMs(long startNanos) {
        return (int) Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
