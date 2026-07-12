package com.traffic.agent.orchestrator;

import java.util.Map;

public record AgentToolExecution(
        String auditId,
        String toolName,
        Map<String, Object> arguments,
        Object result,
        String status,
        int latencyMs,
        String errorMessage
) {
    public boolean success() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
