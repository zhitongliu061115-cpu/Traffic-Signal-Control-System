package com.traffic.agent.tool;

import java.time.Instant;
import java.util.List;

public record AgentToolResult(
        boolean success,
        String toolName,
        Object data,
        List<Evidence> evidence,
        List<String> warnings,
        Instant timestamp
) {
    public record Evidence(
            String source,
            String name,
            String summary,
            Object value
    ) {
    }
}
