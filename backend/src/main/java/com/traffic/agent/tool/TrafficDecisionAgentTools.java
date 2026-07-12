package com.traffic.agent.tool;

import com.traffic.agent.service.DecisionTraceAggregatorService;
import com.traffic.runtime.query.RuntimeQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class TrafficDecisionAgentTools {

    private static final int DEFAULT_LIMIT = 20;

    private final RuntimeQueryService runtimeQueryService;
    private final DecisionTraceAggregatorService decisionTraceAggregatorService;

    public TrafficDecisionAgentTools(
            RuntimeQueryService runtimeQueryService,
            DecisionTraceAggregatorService decisionTraceAggregatorService
    ) {
        this.runtimeQueryService = runtimeQueryService;
        this.decisionTraceAggregatorService = decisionTraceAggregatorService;
    }

    @Tool(name = "get_latest_control_decisions", value = "Query latest control decisions. Read-only.")
    public AgentToolResult getLatestControlDecisions(String sid, String intersectionId, Integer limit) {
        return AgentToolSupport.run(
                "get_latest_control_decisions",
                () -> runtimeQueryService.getLatestControlDecisions(
                        blankToNull(sid),
                        blankToNull(intersectionId),
                        normalizeLimit(limit)
                ),
                "Latest persisted control decisions from database"
        );
    }

    @Tool(name = "get_decision_trace", value = "查询指定控制决策链路，包括 Traffic-R 推理、安全层/fallback 轨迹、MaxPressure 全候选评分、CityFlow 应用结果和后续效果。只读。")
    public AgentToolResult getDecisionTrace(String decisionId) {
        return AgentToolSupport.run(
                "get_decision_trace",
                () -> decisionTraceAggregatorService.getDecisionTrace(decisionId),
                "Aggregated decision trace with Traffic-R inference, safety events, fallback events and CityFlow apply metadata"
        );
    }

    @Tool(name = "get_model_inference_log", value = "Query Traffic-R inference logs. Read-only.")
    public AgentToolResult getModelInferenceLog(String sid, String intersectionId, Integer limit) {
        return AgentToolSupport.run(
                "get_model_inference_log",
                () -> runtimeQueryService.getModelInferenceLog(
                        blankToNull(sid),
                        blankToNull(intersectionId),
                        normalizeLimit(limit)
                ),
                "Traffic-R inference logs from database"
        );
    }

    String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, 100);
    }
}
