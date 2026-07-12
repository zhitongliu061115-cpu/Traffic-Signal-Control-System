package com.traffic.agent.orchestrator;

import java.util.List;
import java.util.Map;

public record AgentPlan(
        String intent,
        boolean needsTools,
        String rationale,
        List<PlannedToolCall> toolCalls,
        String rawPlan,
        String plannerSource,
        boolean fallback
) {
    public AgentPlan withRawPlan(String rawPlan, String plannerSource, boolean fallback) {
        return new AgentPlan(intent, needsTools, rationale, toolCalls, rawPlan, plannerSource, fallback);
    }

    public static AgentPlan directAnswer(String rationale, String rawPlan, String plannerSource, boolean fallback) {
        return new AgentPlan("direct_answer", false, rationale, List.of(), rawPlan, plannerSource, fallback);
    }

    public record PlannedToolCall(
            String toolName,
            Map<String, Object> arguments,
            String reason
    ) {
    }
}
