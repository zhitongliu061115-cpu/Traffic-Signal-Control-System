package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLlmClientTest {

    @Test
    void routesPlanningAndAnswerStagesToDifferentModels() {
        ChatModel plannerModel = mock(ChatModel.class);
        ChatModel answerModel = mock(ChatModel.class);
        ObjectProvider<ChatModel> plannerProvider = provider(plannerModel);
        ObjectProvider<ChatModel> answerProvider = provider(answerModel);
        ChatResponse plannerResponse = response("plan");
        ChatResponse answerResponse = response("answer");
        when(plannerModel.chat(anyList())).thenReturn(plannerResponse);
        when(answerModel.chat(anyList())).thenReturn(answerResponse);
        AgentLlmClient client = new AgentLlmClient(
                plannerProvider,
                answerProvider,
                new AgentDebugLogService(new ObjectMapper())
        );

        assertEquals("plan", client.chat("tool_plan", "system", "user").text());
        verify(plannerModel).chat(anyList());
        verify(answerModel, never()).chat(anyList());

        assertEquals("answer", client.chat("answer", "system", "user").text());
        verify(answerModel).chat(anyList());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatModel> provider(ChatModel model) {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }

    private ChatResponse response(String text) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from(text));
        return response;
    }
}
