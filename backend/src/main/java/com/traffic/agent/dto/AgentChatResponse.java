package com.traffic.agent.dto;

import java.util.List;
import java.util.Map;

public record AgentChatResponse(
        String reply,
        String sessionId,
        String source,
        boolean fallback,
        String conversationId,
        String messageId,
        List<ToolCallSummary> toolCalls,
        List<EvidenceItem> evidence,
        PlanTrace planTrace
) {
    public AgentChatResponse(String reply, String sessionId, String source, boolean fallback) {
        this(reply, sessionId, source, fallback, null, null, List.of(), List.of(), null);
    }

    public record ToolCallSummary(
            String id,
            String toolName,
            Map<String, Object> arguments,
            String status,
            int latencyMs,
            String errorMessage
    ) {
    }

    public record EvidenceItem(
            String source,
            String name,
            String summary,
            Object value
    ) {
    }

    public record PlanTrace(
            String intent,
            String rationale,
            boolean needsTools,
            String rawPlan,
            String plannerSource
    ) {
    }
}
