package com.traffic.agent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(AgentLangChain4jProperties.class)
public class AgentLangChain4jConfig {

    @Bean
    @ConditionalOnProperty(prefix = "traffic.agent.langchain4j", name = "enabled", havingValue = "true")
    public ChatModel agentChatModel(AgentLangChain4jProperties properties) {
        return OpenAiChatModel.builder()
                .baseUrl(normalizeBaseUrl(properties.getBaseUrl()))
                .apiKey(normalizeApiKey(properties.getApiKey()))
                .modelName(normalizeModelName(properties.getModelName()))
                .temperature(properties.getTemperature())
                .timeout(Duration.ofSeconds(Math.max(properties.getTimeoutSeconds(), 1)))
                .maxRetries(1)
                .build();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeApiKey(String apiKey) {
        return StringUtils.hasText(apiKey) ? apiKey.trim() : "EMPTY_API_KEY";
    }

    private String normalizeModelName(String modelName) {
        return StringUtils.hasText(modelName) ? modelName.trim() : "qwen-plus";
    }
}
