package com.traffic.agent.orchestrator;

import com.traffic.agent.dto.AgentDataDtos.ToolCallResponse;
import com.traffic.agent.service.AgentDataService;
import com.traffic.agent.tool.EmergencyAgentTools;
import com.traffic.agent.tool.TrafficDecisionAgentTools;
import com.traffic.agent.tool.TrafficDiagnosisAgentTools;
import com.traffic.agent.tool.TrafficHealthAgentTools;
import com.traffic.agent.tool.TrafficKnowledgeAgentTools;
import com.traffic.agent.tool.TrafficRuntimeAgentTools;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolExecutorTest {

    @Test
    void recordsFailedStatusWhenToolReturnsStructuredFailure() {
        AgentDataService dataService = mock(AgentDataService.class);
        TrafficRuntimeAgentTools runtimeTools = mock(TrafficRuntimeAgentTools.class);
        when(runtimeTools.getCurrentSimulationState("missing"))
                .thenReturn(new com.traffic.agent.tool.AgentToolResult(
                        false,
                        "get_current_simulation_state",
                        null,
                        java.util.List.of(),
                        java.util.List.of("未找到仿真会话：missing"),
                        Instant.parse("2026-07-11T00:00:00Z")
                ));
        when(dataService.recordToolCall(
                eq("message-id"),
                eq("get_current_simulation_state"),
                any(),
                any(),
                eq("FAILED"),
                any(Integer.class),
                eq("未找到仿真会话：missing")
        )).thenReturn(new ToolCallResponse(
                "tool-call-id",
                "message-id",
                "get_current_simulation_state",
                "{}",
                "{}",
                "FAILED",
                1,
                "未找到仿真会话：missing",
                Instant.parse("2026-07-11T00:00:00Z")
        ));

        AgentToolExecutor executor = new AgentToolExecutor(
                dataService,
                runtimeTools,
                mock(TrafficDecisionAgentTools.class),
                mock(TrafficHealthAgentTools.class),
                mock(TrafficKnowledgeAgentTools.class),
                mock(TrafficDiagnosisAgentTools.class),
                mock(EmergencyAgentTools.class)
        );

        AgentToolExecution execution = executor.execute(
                "message-id",
                new AgentPlan.PlannedToolCall("get_current_simulation_state", Map.of("sid", "missing"), "test")
        );

        assertEquals("FAILED", execution.status());
        assertFalse(execution.success());
        assertEquals("未找到仿真会话：missing", execution.errorMessage());
    }

    @Test
    void exposesDiagnosisToolsInWhitelist() {
        AgentToolExecutor executor = new AgentToolExecutor(
                mock(AgentDataService.class),
                mock(TrafficRuntimeAgentTools.class),
                mock(TrafficDecisionAgentTools.class),
                mock(TrafficHealthAgentTools.class),
                mock(TrafficKnowledgeAgentTools.class),
                mock(TrafficDiagnosisAgentTools.class),
                mock(EmergencyAgentTools.class)
        );

        assertTrue(executor.allowedTools().contains("diagnose_congestion"));
        assertTrue(executor.allowedTools().contains("detect_signal_anomaly"));
        assertTrue(executor.allowedTools().contains("detect_spillback_risk"));
        assertTrue(executor.allowedTools().contains("get_safety_constraint_log"));
        assertTrue(executor.allowedTools().contains("get_fallback_log"));
        assertTrue(executor.allowedTools().contains("get_region_metrics"));
        assertTrue(executor.allowedTools().contains("compare_strategy_metrics"));
    }
}
