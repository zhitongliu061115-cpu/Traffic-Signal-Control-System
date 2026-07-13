package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentIntentClassifierTest {

    @Test
    void planParsesLlmJsonAndFiltersUnknownTools() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.allowedTools()).thenReturn(List.of("get_current_simulation_state"));
        when(llmClient.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        ))
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
        when(llmClient.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        ))
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

    @Test
    void placeholderEmergencyArgumentsAreNotExecuted() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.allowedTools()).thenReturn(List.of("draft_emergency_dispatch"));
        when(llmClient.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        ))
                .thenReturn(new AgentLlmClient.LlmResult("""
                        {
                          "intent": "emergency",
                          "needsTools": true,
                          "rationale": "用户要求生成调度建议",
                          "toolCalls": [
                            {
                              "toolName": "draft_emergency_dispatch",
                              "arguments": {"startIntersection": "待用户提供", "endIntersection": "B"},
                              "reason": "错误地填入占位参数"
                            }
                          ]
                        }
                        """, "test", false));

        AgentIntentClassifier classifier = new AgentIntentClassifier(llmClient, toolExecutor, new ObjectMapper());

        AgentPlan plan = classifier.plan(new AgentIntentClassifier.AgentPlanningInput(
                "生成调度建议",
                "run_001",
                "{}"
        ));

        assertEquals("emergency", plan.intent());
        assertFalse(plan.needsTools());
        assertEquals(0, plan.toolCalls().size());
        assertTrue(plan.rationale().contains("后端已阻止"));
    }

    @Test
    void realtimeCongestionQuestionUsesLiveDiagnosisInsteadOfHistoricalRegionMetrics() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.allowedTools()).thenReturn(List.of("get_region_metrics", "diagnose_congestion"));
        when(llmClient.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        ))
                .thenReturn(new AgentLlmClient.LlmResult("""
                        {
                          "intent": "diagnosis",
                          "needsTools": true,
                          "rationale": "查询拥堵排名",
                          "toolCalls": [
                            {
                              "toolName": "get_region_metrics",
                              "arguments": {},
                              "reason": "模型错误选择了历史区域统计"
                            }
                          ]
                        }
                        """, "test", false));

        AgentIntentClassifier classifier = new AgentIntentClassifier(llmClient, toolExecutor, new ObjectMapper());

        AgentPlan plan = classifier.plan(new AgentIntentClassifier.AgentPlanningInput(
                "当前哪个路口最拥堵？",
                "run_001",
                "{}"
        ));

        assertTrue(plan.needsTools());
        assertEquals(1, plan.toolCalls().size());
        assertEquals("diagnose_congestion", plan.toolCalls().get(0).toolName());
        assertEquals("run_001", plan.toolCalls().get(0).arguments().get("sid"));
    }
}
