package com.traffic.agent.tool;

import com.traffic.common.exception.BusinessException;
import com.traffic.runtime.query.RuntimeQueryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrafficAgentToolsTest {

    @Test
    void runtimeToolReturnsStructuredFailureInsteadOfThrowing() {
        RuntimeQueryService runtimeQueryService = mock(RuntimeQueryService.class);
        when(runtimeQueryService.getCurrentSimulationState("missing"))
                .thenThrow(new BusinessException("未找到仿真会话：missing"));
        TrafficRuntimeAgentTools tools = new TrafficRuntimeAgentTools(runtimeQueryService);

        AgentToolResult result = tools.getCurrentSimulationState("missing");

        assertFalse(result.success());
        assertTrue(result.warnings().get(0).contains("未找到仿真会话"));
        assertNotNull(result.timestamp());
    }

    @Test
    void knowledgeToolSearchesLocalProjectDocs() {
        TrafficKnowledgeAgentTools tools = new TrafficKnowledgeAgentTools();

        AgentToolResult result = tools.searchKnowledgeBase("Agent", 3, null);

        assertTrue(result.success());
        assertFalse(((TrafficKnowledgeAgentTools.KnowledgeSearchResponse) result.data()).hits().isEmpty());
        assertNotNull(result.timestamp());
    }
}
