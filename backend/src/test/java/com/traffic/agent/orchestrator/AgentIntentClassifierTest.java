package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void emergencyDraftUsesRememberedDispatchEndpointsWhenUserAsksForSuggestion() {
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
                          "needsTools": false,
                          "rationale": "缺少起点和终点",
                          "toolCalls": []
                        }
                        """, "test", false));

        AgentIntentClassifier classifier = new AgentIntentClassifier(llmClient, toolExecutor, new ObjectMapper());

        AgentPlan plan = classifier.plan(new AgentIntentClassifier.AgentPlanningInput(
                "生成调度建议",
                "run_001",
                """
                        {
                          "sid": "run_001",
                          "emergencyDispatchMemory": {
                            "startIntersection": "intersection_1_1",
                            "endIntersection": "intersection_1_2",
                            "evId": "EV-1",
                            "evType": "ambulance",
                            "priority": 3
                          }
                        }
                        """
        ));

        assertTrue(plan.needsTools());
        assertEquals(1, plan.toolCalls().size());
        assertEquals("draft_emergency_dispatch", plan.toolCalls().get(0).toolName());
        assertEquals("run_001", plan.toolCalls().get(0).arguments().get("sid"));
        assertEquals("intersection_1_1", plan.toolCalls().get(0).arguments().get("startIntersection"));
        assertEquals("intersection_1_2", plan.toolCalls().get(0).arguments().get("endIntersection"));
        assertEquals("EV-1", plan.toolCalls().get(0).arguments().get("evId"));
        assertEquals("ambulance", plan.toolCalls().get(0).arguments().get("evType"));
        assertEquals(3, plan.toolCalls().get(0).arguments().get("priority"));
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

    @Test
    void realtimeMostCongestedQuestionDoesNotRequireSpecificIntersectionId() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.allowedTools()).thenReturn(List.of("get_intersection_detail", "diagnose_congestion"));
        when(llmClient.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        ))
                .thenReturn(new AgentLlmClient.LlmResult("""
                        {
                          "intent": "diagnosis",
                          "needsTools": true,
                          "rationale": "模型误以为需要用户指定目标路口",
                          "toolCalls": [
                            {
                              "toolName": "get_intersection_detail",
                              "arguments": {},
                              "reason": "错误地要求具体路口 ID"
                            }
                          ]
                        }
                        """, "test", false));

        AgentIntentClassifier classifier = new AgentIntentClassifier(llmClient, toolExecutor, new ObjectMapper());

        AgentPlan plan = classifier.plan(new AgentIntentClassifier.AgentPlanningInput(
                "哪个路口最拥堵？",
                "run_001",
                "{}"
        ));

        assertTrue(plan.needsTools());
        assertEquals(1, plan.toolCalls().size());
        assertEquals("diagnose_congestion", plan.toolCalls().get(0).toolName());
        assertEquals("run_001", plan.toolCalls().get(0).arguments().get("sid"));
        assertFalse(plan.toolCalls().get(0).arguments().containsKey("targetId"));
    }

    @Test
    void plannerPromptKeepsSpillbackArgumentsOptional() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        when(toolExecutor.allowedTools()).thenReturn(List.of("detect_spillback_risk"));
        when(llmClient.chat(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(new AgentLlmClient.LlmResult("""
                {
                  "intent": "direct_answer",
                  "needsTools": false,
                  "rationale": "缺少具体道路或路口",
                  "toolCalls": []
                }
                """, "test", false));

        AgentIntentClassifier classifier = new AgentIntentClassifier(llmClient, toolExecutor, new ObjectMapper());
        classifier.plan(new AgentIntentClassifier.AgentPlanningInput(
                "检测下游溢出风险，但没有指定目标",
                "run_001",
                "{}"
        ));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(
                org.mockito.ArgumentMatchers.eq("tool_plan"),
                systemPrompt.capture(),
                org.mockito.ArgumentMatchers.anyString()
        );
        assertTrue(systemPrompt.getValue().contains("参数：sid?，roadId?，intersectionId?，sceneCode?"));
        assertFalse(systemPrompt.getValue().contains("intent 使用 direct_answer"));
    }
}
