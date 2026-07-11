package com.traffic.agent.orchestrator;

import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse;
import com.traffic.agent.service.BailianAgentService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentLlmClient {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final BailianAgentService bailianAgentService;

    public AgentLlmClient(ObjectProvider<ChatModel> chatModelProvider, BailianAgentService bailianAgentService) {
        this.chatModelProvider = chatModelProvider;
        this.bailianAgentService = bailianAgentService;
    }

    public LlmResult chat(String systemPrompt, String userPrompt) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null) {
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)
            ));
            String text = response.aiMessage() == null ? "" : response.aiMessage().text();
            return new LlmResult(StringUtils.hasText(text) ? text : "", "langchain4j", false);
        }

        AgentChatResponse response = bailianAgentService.chat(new AgentChatRequest(
                systemPrompt + "\n\n" + userPrompt,
                null,
                null,
                null,
                Map.of()
        ));
        return new LlmResult(response.reply(), response.source(), response.fallback());
    }

    public record LlmResult(
            String text,
            String source,
            boolean fallback
    ) {
    }
}
