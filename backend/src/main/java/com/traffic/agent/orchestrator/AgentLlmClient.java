package com.traffic.agent.orchestrator;

import com.traffic.common.exception.BusinessException;
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
    private final AgentDebugLogService debugLogService;

    public AgentLlmClient(ObjectProvider<ChatModel> chatModelProvider, AgentDebugLogService debugLogService) {
        this.chatModelProvider = chatModelProvider;
        this.debugLogService = debugLogService;
    }

    public LlmResult chat(String systemPrompt, String userPrompt) {
        return chat("unknown", systemPrompt, userPrompt);
    }

    public LlmResult chat(String stage, String systemPrompt, String userPrompt) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            debugLogService.warn("agent.llm.not_configured", Map.of("stage", stage));
            throw new BusinessException(
                    "Agent LLM is not configured: set LLM_API_KEY or DASHSCOPE_API_KEY "
                            + "and enable traffic.agent.langchain4j.enabled"
            );
        }

        debugLogService.info("agent.llm.request", Map.of(
                "stage", stage,
                "systemPrompt", systemPrompt,
                "userPrompt", userPrompt
        ));
        long startNanos = System.nanoTime();
        try {
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)
            ));
            String text = response.aiMessage() == null ? "" : response.aiMessage().text();
            debugLogService.info("agent.llm.response", Map.of(
                    "stage", stage,
                    "latencyMs", elapsedMs(startNanos),
                    "text", text
            ));
            return new LlmResult(StringUtils.hasText(text) ? text : "", "llm-api", false);
        } catch (RuntimeException ex) {
            debugLogService.error("agent.llm.error", Map.of(
                    "stage", stage,
                    "latencyMs", elapsedMs(startNanos),
                    "error", errorMessage(ex)
            ), ex);
            throw new BusinessException("Agent LLM call failed: " + errorMessage(ex));
        }
    }

    private String errorMessage(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private int elapsedMs(long startNanos) {
        return (int) Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    public record LlmResult(
            String text,
            String source,
            boolean fallback
    ) {
    }
}
