package com.traffic.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse;
import com.traffic.common.exception.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class BailianAgentService {

    private static final int MAX_CONTEXT_CHARS = 2400;

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是"城市交通信号调度辅助决策智能体"，服务于"信号灯配时控制与应急通行信控系统"。你具备交通工程与信号控制领域的专业知识，熟悉自适应信号控制、干线绿波、应急车辆优先，核心是Traffic-R1策略，还了解 FixedTime、MaxPressure、强化学习等控制策略，理解平均排队长度、累计排队车辆数、平均等待时间、平均旅行时间、通行量等评价指标。

            你的职责是：查询并解读实时路网状态、诊断拥堵成因、生成可审计的信号调度与应急通行建议、回答交通领域知识问答。

            你只提供"建议"与"待确认方案"，绝不代替系统直接下发或执行任何信号控制指令。所有控制建议必须经后端校验与人工确认后才可能生效。

            ## 技能
            1. 实时路网状态查询与解读：涉及实时数值时必须基于系统传入的上下文，缺数据时明确说明暂时无法获取实时数据。
            2. 拥堵诊断与归因：说明哪里堵、为什么堵、影响范围有多大。
            3. 信号调度建议生成：给出下一阶段放行方向、建议时长与协同方案，并说明依据与预期收益。
            4. 应急通行建议：涉及救护车、消防车等应急车辆时，给出沿线路口放行顺序、相位优先方案和恢复建议。
            5. 交通知识问答：回答信号控制、绿波、评价指标、算法概念时，说明内容来源于系统知识或通用专业知识。

            ## 输出格式
            一般问答：先结论、后依据，语言简洁专业。
            控制/绿波建议：自然语言说明后附结构化 JSON：
            {
              "type": "signal_adjust | emergency_greenwave",
              "targets": ["路口或路段ID"],
              "recommendation": "建议的相位 / 放行方向 / 时长 / 顺序",
              "basis": {"关键指标名": "取值"},
              "expected_effect": "预期收益",
              "confidence": "高 / 中 / 低",
              "risk": "潜在风险或不确定性",
              "status": "建议-待人工确认"
            }

            ## 限制
            不声称已执行、已下发或已生效任何信号控制指令；不编造路口 ID、指标数值或告警事件；只处理城市交通信号控制与调度相关问题。
            """;

    private final String appId;
    private final String apiKey;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BailianAgentService(
            @Value("${bailian.base-url}") String baseUrl,
            @Value("${bailian.app-id}") String appId,
            @Value("${bailian.api-key}") String apiKey,
            ObjectMapper objectMapper
    ) {
        this.appId = appId;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(removeTrailingSlash(baseUrl))
                .build();
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        if (!StringUtils.hasText(appId)) {
            return configFallback("百炼应用 ID 未配置，请设置 BAILIAN_APP_ID 或 application.yml 中的 bailian.app-id。");
        }
        if (!StringUtils.hasText(apiKey)) {
            return configFallback("百炼 API Key 未配置，请在后端环境变量 BAILIAN_API_KEY 中设置后重启服务。");
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", buildPrompt(request));
        if (StringUtils.hasText(request.sessionId())) {
            input.put("session_id", request.sessionId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", input);
        payload.put("parameters", Map.of("incremental_output", false));
        payload.put("debug", Map.of());

        try {
            JsonNode response = restClient.post()
                    .uri("/apps/{appId}/completion", appId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(payload)
                    .retrieve()
                    .onStatus(status -> status.isError(), (request_, response_) -> {
                        throw new BusinessException("百炼调用失败：HTTP " + response_.getStatusCode().value());
                    })
                    .body(JsonNode.class);

            String reply = extractReply(response);
            if (!StringUtils.hasText(reply)) {
                throw new BusinessException("百炼返回为空，请检查应用发布状态和调用权限。");
            }

            return new AgentChatResponse(reply, extractSessionId(response), "bailian", false);
        } catch (RestClientException ex) {
            throw new BusinessException("百炼调用失败，请检查网络、API Key、应用 ID 与百炼服务状态。");
        }
    }

    private String buildPrompt(AgentChatRequest request) {
        return SYSTEM_PROMPT
                + "\n\n当前路网上下文（仅可基于这些数据作实时判断，缺失则说明无法获取）：\n"
                + serializeContext(request.context())
                + "\n\n用户问题：\n"
                + request.message();
    }

    private String serializeContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "{}";
        }
        try {
            String json = objectMapper.writeValueAsString(context);
            if (json.length() <= MAX_CONTEXT_CHARS) {
                return json;
            }
            return json.substring(0, MAX_CONTEXT_CHARS) + "...";
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private AgentChatResponse configFallback(String message) {
        return new AgentChatResponse(message, null, "config", true);
    }

    private String extractReply(JsonNode response) {
        if (response == null) {
            return null;
        }

        JsonNode output = response.path("output");
        String text = output.path("text").asText(null);
        if (StringUtils.hasText(text)) {
            return text;
        }

        JsonNode choices = output.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode first = choices.get(0);
            String content = first.path("message").path("content").asText(null);
            if (StringUtils.hasText(content)) {
                return content;
            }
        }

        return response.path("message").path("content").asText(null);
    }

    private String extractSessionId(JsonNode response) {
        if (response == null) {
            return null;
        }
        String sessionId = response.path("output").path("session_id").asText(null);
        return StringUtils.hasText(sessionId) ? sessionId : null;
    }

    private String removeTrailingSlash(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://dashscope.aliyuncs.com/api/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
