package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentResponseAssemblerTest {

    @Test
    void extractsFinalConclusionWhenModelReturnsJsonProcessPayload() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        when(llmClient.chat(eq("answer"), anyString(), anyString())).thenReturn(new AgentLlmClient.LlmResult("""
                {
                  "evidenceList": [
                    {"source": "backend-service", "name": "get_current_simulation_state", "value": {"vehicleCount": 13}}
                  ],
                  "actionPlan": [
                    {"title": "优化信号周期", "details": "增加部分路口绿灯时间"}
                  ],
                  "conclusion": "基于当前仿真状态和控制决策日志，建议暂不直接切换策略，仅持续监控并保留安全层校验。"
                }
                """, "test", false));
        AgentResponseAssembler assembler = new AgentResponseAssembler(llmClient, new ObjectMapper());

        AgentResponseAssembler.AgentAnswer answer = assembler.assemble(
                new AgentChatRequest("生成调度建议", "session-1", "run-1", null, Map.of()),
                new AgentContextBuilder.AgentContext("run-1", "{}"),
                new AgentPlan("diagnosis", true, "需要工具", List.of(), "{}", "test", false),
                List.of()
        );

        assertTrue(answer.reply().contains("建议暂不直接切换策略"));
        assertFalse(answer.reply().contains("evidenceList"));
        assertFalse(answer.reply().contains("actionPlan"));
        assertFalse(answer.reply().trim().startsWith("{"));
    }

    @Test
    void extractsNestedContentConclusionWhenModelReturnsFrontendShape() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        when(llmClient.chat(eq("answer"), anyString(), anyString())).thenReturn(new AgentLlmClient.LlmResult("""
                {"intent":"current_state","responseType":"summary","content":{"conclusion":"当前仿真运行稳定，建议维持现有策略并继续观察。","evidenceList":[],"actionPlan":[]}}
                """, "test", false));
        AgentResponseAssembler assembler = new AgentResponseAssembler(llmClient, new ObjectMapper());

        AgentResponseAssembler.AgentAnswer answer = assembler.assemble(
                new AgentChatRequest("当前路网状态", "session-1", "run-1", null, Map.of()),
                new AgentContextBuilder.AgentContext("run-1", "{}"),
                new AgentPlan("current_state", true, "需要工具", List.of(), "{}", "test", false),
                List.of()
        );

        assertTrue(answer.reply().contains("当前仿真运行稳定"));
        assertFalse(answer.reply().contains("responseType"));
        assertFalse(answer.reply().contains("evidenceList"));
        assertFalse(answer.reply().trim().startsWith("{"));
    }

    @Test
    void insertsLineBreaksWhenModelReturnsInlineNumberedList() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        when(llmClient.chat(eq("answer"), anyString(), anyString())).thenReturn(new AgentLlmClient.LlmResult(
                "交通信号灯的通信协议主要包括以下几种： 1. GB/T 39900-2021：规定通信流程。 2. GA/T 1049：规定平台数据通信。 3. GB/T 43229-2023：规定检测器通信。",
                "test",
                false
        ));
        AgentResponseAssembler assembler = new AgentResponseAssembler(llmClient, new ObjectMapper());

        AgentResponseAssembler.AgentAnswer answer = assembler.assemble(
                new AgentChatRequest("交通信号灯的通信协议有哪些", "session-1", "run-1", null, Map.of()),
                new AgentContextBuilder.AgentContext("run-1", "{}"),
                new AgentPlan("knowledge", true, "需要知识库", List.of(), "{}", "test", false),
                List.of()
        );

        assertTrue(answer.reply().contains("：\n1."));
        assertTrue(answer.reply().contains("\n2."));
        assertTrue(answer.reply().contains("\n3."));
    }

    @Test
    void insertsLineBreaksWhenModelReturnsEscapedNewlineOrWideSpaces() {
        AgentLlmClient llmClient = mock(AgentLlmClient.class);
        when(llmClient.chat(eq("answer"), anyString(), anyString())).thenReturn(new AgentLlmClient.LlmResult(
                "结论：\\n1. 第一项。　2. 第二项。 3. 第三项。",
                "test",
                false
        ));
        AgentResponseAssembler assembler = new AgentResponseAssembler(llmClient, new ObjectMapper());

        AgentResponseAssembler.AgentAnswer answer = assembler.assemble(
                new AgentChatRequest("列出规则", "session-1", "run-1", null, Map.of()),
                new AgentContextBuilder.AgentContext("run-1", "{}"),
                new AgentPlan("knowledge", true, "需要知识库", List.of(), "{}", "test", false),
                List.of()
        );

        assertTrue(answer.reply().contains("结论：\n1."));
        assertTrue(answer.reply().contains("\n2."));
        assertTrue(answer.reply().contains("\n3."));
    }
}
