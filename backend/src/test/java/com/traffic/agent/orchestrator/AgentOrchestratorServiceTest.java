package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse.EvidenceItem;
import com.traffic.agent.dto.AgentChatResponse.ToolCallSummary;
import com.traffic.agent.dto.AgentDataDtos.ConversationResponse;
import com.traffic.agent.dto.AgentDataDtos.CreateConversationRequest;
import com.traffic.agent.dto.AgentDataDtos.CreateMessageRequest;
import com.traffic.agent.dto.AgentDataDtos.MessageResponse;
import com.traffic.agent.dto.AgentDataDtos.ToolCallResponse;
import com.traffic.agent.service.AgentDataService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorServiceTest {

    @Test
    void chatPersistsMessagesRecordsPlanExecutesToolsAndReturnsSummary() {
        AgentDataService dataService = mock(AgentDataService.class);
        AgentContextBuilder contextBuilder = new AgentContextBuilder(new ObjectMapper());
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        AgentResponseAssembler assembler = mock(AgentResponseAssembler.class);
        AgentOrchestratorService orchestrator = new AgentOrchestratorService(
                dataService,
                contextBuilder,
                classifier,
                toolExecutor,
                assembler,
                new ObjectMapper()
        );

        ConversationResponse conversation = new ConversationResponse(
                "11111111-1111-1111-1111-111111111111",
                null,
                "run_001",
                null,
                "当前仿真状态怎么样？",
                Instant.parse("2026-07-11T00:00:00Z"),
                Instant.parse("2026-07-11T00:00:00Z")
        );
        MessageResponse userMessage = new MessageResponse(
                "22222222-2222-2222-2222-222222222222",
                conversation.id(),
                "user",
                "当前仿真状态怎么样？",
                Instant.parse("2026-07-11T00:00:01Z")
        );
        MessageResponse assistantMessage = new MessageResponse(
                "33333333-3333-3333-3333-333333333333",
                conversation.id(),
                "assistant",
                "当前仿真运行正常。",
                Instant.parse("2026-07-11T00:00:02Z")
        );
        when(dataService.createConversation(any(CreateConversationRequest.class))).thenReturn(conversation);
        when(dataService.createMessage(eq(conversation.id()), any(CreateMessageRequest.class)))
                .thenReturn(userMessage)
                .thenReturn(assistantMessage);
        when(dataService.recordToolCall(
                eq(userMessage.id()),
                eq("llm_tool_plan"),
                any(),
                any(),
                eq("SUCCESS"),
                eq(0),
                org.mockito.ArgumentMatchers.isNull()
        )).thenReturn(new ToolCallResponse(
                "44444444-4444-4444-4444-444444444444",
                userMessage.id(),
                "llm_tool_plan",
                "{}",
                "{}",
                "SUCCESS",
                0,
                null,
                Instant.parse("2026-07-11T00:00:01Z")
        ));

        AgentPlan.PlannedToolCall plannedToolCall = new AgentPlan.PlannedToolCall(
                "get_current_simulation_state",
                Map.of("sid", "run_001"),
                "查询当前状态"
        );
        AgentPlan plan = new AgentPlan(
                "current_state",
                true,
                "需要真实状态",
                List.of(plannedToolCall),
                "{\"needsTools\":true}",
                "test",
                false
        );
        when(classifier.plan(any())).thenReturn(plan);

        AgentToolExecution execution = new AgentToolExecution(
                "55555555-5555-5555-5555-555555555555",
                "get_current_simulation_state",
                Map.of("sid", "run_001"),
                Map.of("status", "running"),
                "SUCCESS",
                12,
                null
        );
        when(toolExecutor.execute(userMessage.id(), plannedToolCall, "run_001")).thenReturn(execution);
        when(assembler.assemble(any(), any(), eq(plan), eq(List.of(execution)))).thenReturn(
                new AgentResponseAssembler.AgentAnswer(
                        "当前仿真运行正常。",
                        "test",
                        false,
                        List.of(new EvidenceItem("tool", "get_current_simulation_state", "返回状态", Map.of("status", "running"))),
                        List.of(new ToolCallSummary(
                                execution.auditId(),
                                execution.toolName(),
                                execution.arguments(),
                                execution.status(),
                                execution.latencyMs(),
                                execution.errorMessage()
                        ))
                )
        );

        var response = orchestrator.chat(new AgentChatRequest(
                "当前仿真状态怎么样？",
                null,
                "run_001",
                null,
                Map.of()
        ));

        assertEquals("当前仿真运行正常。", response.reply());
        assertEquals(conversation.id(), response.conversationId());
        assertEquals(assistantMessage.id(), response.messageId());
        assertEquals(1, response.toolCalls().size());
        assertFalse(response.fallback());
        verify(dataService).createConversation(any(CreateConversationRequest.class));
        verify(dataService).recordToolCall(eq(userMessage.id()), eq("llm_tool_plan"), any(), any(), eq("SUCCESS"), eq(0), org.mockito.ArgumentMatchers.isNull());
        verify(toolExecutor).execute(userMessage.id(), plannedToolCall, "run_001");
    }
}
