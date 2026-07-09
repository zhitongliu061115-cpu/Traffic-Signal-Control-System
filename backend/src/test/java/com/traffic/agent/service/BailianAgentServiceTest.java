package com.traffic.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BailianAgentServiceTest {

    @Test
    void returnsConfigFallbackWhenApiKeyMissing() {
        BailianAgentService service = new BailianAgentService(
                "https://dashscope.aliyuncs.com/api/v1",
                "test-app",
                "",
                new ObjectMapper()
        );

        AgentChatResponse response = service.chat(new AgentChatRequest("当前路网状态如何？", null, Map.of()));

        assertThat(response.fallback()).isTrue();
        assertThat(response.source()).isEqualTo("config");
        assertThat(response.reply()).contains("BAILIAN_API_KEY");
    }
}
