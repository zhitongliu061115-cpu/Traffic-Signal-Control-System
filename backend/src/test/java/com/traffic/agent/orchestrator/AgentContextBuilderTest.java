package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.service.AgentEmergencyDispatchMemory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextBuilderTest {

    @Test
    void contextIncludesLatestEmergencyDispatchEndpointsForSid() {
        AgentEmergencyDispatchMemory memory = new AgentEmergencyDispatchMemory();
        memory.remember("run_001", "intersection_1_1", "intersection_1_2", "EV-1", "ambulance", 3);
        AgentContextBuilder builder = new AgentContextBuilder(new ObjectMapper(), memory);

        AgentContextBuilder.AgentContext context = builder.build(new AgentChatRequest(
                "生成调度建议",
                null,
                "run_001",
                null,
                Map.of()
        ));

        assertTrue(context.contextJson().contains("\"emergencyDispatchMemory\""));
        assertTrue(context.contextJson().contains("\"startIntersection\":\"intersection_1_1\""));
        assertTrue(context.contextJson().contains("\"endIntersection\":\"intersection_1_2\""));
    }
}
