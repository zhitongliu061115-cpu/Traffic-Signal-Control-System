package com.traffic.agent.tool;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

final class AgentToolSupport {

    private AgentToolSupport() {
    }

    static AgentToolResult success(String toolName, Object data, String evidenceSummary) {
        return new AgentToolResult(
                true,
                toolName,
                data,
                List.of(new AgentToolResult.Evidence("backend-service", toolName, evidenceSummary, data)),
                List.of(),
                Instant.now()
        );
    }

    static AgentToolResult run(String toolName, Supplier<Object> supplier, String evidenceSummary) {
        try {
            return success(toolName, supplier.get(), evidenceSummary);
        } catch (RuntimeException ex) {
            return failure(toolName, ex);
        }
    }

    static AgentToolResult failure(String toolName, RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return new AgentToolResult(
                false,
                toolName,
                null,
                List.of(),
                List.of(message),
                Instant.now()
        );
    }
}
