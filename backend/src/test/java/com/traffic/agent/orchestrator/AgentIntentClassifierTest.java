package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentIntentClassifierTest {

    @Test
    void planParsesLlmJsonAndFiltersUnknownTools() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.allowedTools()).thenReturn(List.of("get_current_simulation_state"));
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AgentLlmClient.LlmResult("""
                        ```json
                        {
                          "intent": "current_state",
                          "needsTools": true,
                          "rationale": "需要查询真实仿真状态",
                          "toolCalls": [
                            {
                              "toolName": "get_current_simulation_state",
                              "arguments": {"sid": "run_001"},
                              "reason": "查询当前状态"
                            },
                            {
                              "toolName": "set_signal_phase",
                              "arguments": {"phase": 1},
                              "reason": "非法执行类工具"
                            }
                          ]
                        }
                        ```
                        """, "test", false));

        AgentIntentClassifier classifier = new AgentIntentClassifier(llmClient, toolExecutor, new ObjectMapper());

        AgentPlan plan = classifier.plan(new AgentIntentClassifier.AgentPlanningInput(
                "当前仿真状态怎么样？",
                "run_001",
                "{}"
        ));

        assertEquals("current_state", plan.intent());
        assertTrue(plan.needsTools());
        assertEquals(1, plan.toolCalls().size());
        assertEquals("get_current_simulation_state", plan.toolCalls().get(0).toolName());
        assertEquals("run_001", plan.toolCalls().get(0).arguments().get("sid"));
    }

    @Test
    void malformedPlanFallsBackToDirectAnswerWithoutToolGuessing() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.allowedTools()).thenReturn(List.of("get_current_simulation_state"));
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AgentLlmClient.LlmResult("我觉得应该查询状态，但这里不是 JSON", "test", false));

        AgentIntentClassifier classifier = new AgentIntentClassifier(llmClient, toolExecutor, new ObjectMapper());

        AgentPlan plan = classifier.plan(new AgentIntentClassifier.AgentPlanningInput(
                "当前仿真状态怎么样？",
                "run_001",
                "{}"
        ));

        assertEquals("direct_answer", plan.intent());
        assertTrue(plan.fallback());
        assertEquals(0, plan.toolCalls().size());
    }
}
