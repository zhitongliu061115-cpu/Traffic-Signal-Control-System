package com.traffic.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse;
import com.traffic.common.exception.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BailianAgentServiceTest {

    private MockRestServiceServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.verify();
        }
    }

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
        assertThat(response.reply()).contains("DASHSCOPE_API_KEY").contains("BAILIAN_API_KEY");
    }

    @Test
    void exposesBailianUnauthorizedReasonWithoutLeakingApiKey() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/api/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        BailianAgentService service = new BailianAgentService(
                "https://dashscope.aliyuncs.com/api/v1",
                "app-1234567890",
                "sk-test-secret",
                new ObjectMapper(),
                builder.build()
        );

        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/apps/app-1234567890/completion"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-secret"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"InvalidApiKey\",\"message\":\"API-key is blocked.\"}"));

        assertThatThrownBy(() -> service.chat(new AgentChatRequest("当前路网状态如何？", null, Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("DASHSCOPE_API_KEY")
                .hasMessageContaining("重新创建 API Key")
                .hasMessageContaining("InvalidApiKey")
                .hasMessageNotContaining("sk-test-secret");
    }

    @Test
    void normalizesBailianConfigBeforeCalling() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/api/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        BailianAgentService service = new BailianAgentService(
                " https://dashscope.aliyuncs.com/api/v1/ ",
                " app-1234567890 ",
                " \"sk-test-secret\" ",
                new ObjectMapper(),
                builder.build()
        );

        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/apps/app-1234567890/completion"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-secret"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"output\":{\"text\":\"百炼连接测试成功\",\"session_id\":\"session-1\"},\"request_id\":\"req-1\"}"));

        AgentChatResponse response = service.chat(new AgentChatRequest("当前路网状态如何？", null, Map.of()));

        assertThat(response.reply()).isEqualTo("百炼连接测试成功");
        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.source()).isEqualTo("bailian");
        assertThat(response.fallback()).isFalse();
    }

    @Test
    void callsOpenAiCompatibleEndpointWhenConfigured() {
        RestClient.Builder compatibleBuilder = RestClient.builder()
                .baseUrl("https://example.aliyuncs.com/compatible-mode/v1");
        server = MockRestServiceServer.bindTo(compatibleBuilder).build();
        BailianAgentService service = new BailianAgentService(
                "https://example.aliyuncs.com/api/v1",
                "",
                "sk-test-secret",
                "",
                "qwen-plus",
                "compatible",
                new ObjectMapper(),
                RestClient.builder().baseUrl("https://example.aliyuncs.com/api/v1").build(),
                compatibleBuilder.build()
        );

        server.expect(requestTo("https://example.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-secret"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        AgentChatResponse response = service.chat(new AgentChatRequest("ping", null, Map.of()));

        assertThat(response.reply()).isEqualTo("ok");
        assertThat(response.sessionId()).isNull();
        assertThat(response.source()).isEqualTo("bailian");
        assertThat(response.fallback()).isFalse();
    }
}
