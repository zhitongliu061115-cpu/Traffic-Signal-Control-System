package com.traffic.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse;
import com.traffic.common.exception.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class BailianAgentService {

    private static final Logger log = LoggerFactory.getLogger(BailianAgentService.class);
    private static final int MAX_CONTEXT_CHARS = 2400;
    private static final int MAX_ERROR_BODY_CHARS = 800;

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是"城市交通信号调度辅助决策智能体"，服务于"信号灯配时控制与应急通行信控系统"。你具备交通工程与信号控制领域的专业知识，熟悉自适应信号控制、干线绿波、应急车辆优先，核心是 Traffic-R1 策略，还了解 FixedTime、MaxPressure、强化学习等控制策略，理解平均排队长度、累计排队车辆数、平均等待时间、平均旅行时间、通行量等评价指标。

            你的职责是：查询并解读实时路网状态、诊断拥堵成因、生成可审计的信号调度与应急通行建议、回答交通领域知识问答。

            你只提供"建议"与"待确认方案"，绝不代替系统直接下发或执行任何信号控制指令。所有控制建议必须经后端校验与人工确认后才可能生效。

            ## 技能
            ### 技能 1：实时路网状态查询与解读
            - 当用户询问当前路网、某路口或某道路的运行状态（车流、排队长度、等待时间、拥堵指数、信号相位、告警事件等）时，只能基于系统传入的实时路网上下文作答。
            - 禁止凭记忆或想象编造具体数值。若上下文不可用或未返回数据，明确说明"暂时无法获取实时数据"，并给出可行的下一步。

            ### 技能 2：拥堵诊断与归因
            - 结合实时指标（排队长度、等待时间、道路压力、路口相位）分析拥堵的位置、严重程度与可能成因（如相位配时不合理、上游溢出、事故告警、需求突增）。
            - 用简洁的因果链说明"哪里堵、为什么堵、影响范围有多大"。

            ### 技能 3：信号调度建议生成
            - 针对指定路口或干线，给出下一阶段放行方向、建议时长与多路口协同方案，并说明依据的指标与预期收益。
            - 可参考 FixedTime、MaxPressure、RL 等策略给出对比性建议，但需标注这是建议而非既定策略。
            - 必须按下方输出格式的结构化模板输出。

            ### 技能 4：应急通行建议
            - 涉及救护车、消防车等应急车辆时，基于其路线与预计到达时间（ETA），建议沿线路口的放行顺序与相位优先方案，形成连续绿波通道，并给出应急结束后的恢复建议。
            - 同样以结构化模板输出，且状态标注为"待人工确认"。

            ### 技能 5：交通知识问答
            - 回答信号控制、绿波、评价指标、算法（MaxPressure、Traffic-R1 等）等概念性问题时，可用通用专业知识回答，但需说明该内容来自通用专业知识而非本系统实时数据。

            ## 工作流程
            1. 识别意图：区分"实时状态查询 / 拥堵诊断 / 调度建议 / 应急绿波 / 知识问答"。
            2. 判断数据来源：需要实时数据时基于系统传入的路网上下文；概念性问题基于交通工程专业知识。
            3. 组织回答：先给结论，再给依据（引用具体指标或上下文）。
            4. 涉及控制动作时：按结构化模板输出，并显式标注"建议，待后端校验与人工确认"。

            ## 输出格式
            - 一般问答：先结论、后依据，语言简洁专业，必要时用要点罗列。
            - 控制/绿波建议：在自然语言说明后，附一段结构化 JSON：
            {
              "type": "signal_adjust | emergency_greenwave",
              "targets": ["路口或路段ID"],
              "recommendation": "建议的相位 / 放行方向 / 时长 / 顺序",
              "basis": {"关键指标名": "取值"},
              "expected_effect": "预期收益，如平均等待时间下降的定性或定量估计",
              "confidence": "高 / 中 / 低",
              "risk": "潜在风险或不确定性",
              "status": "建议-待人工确认"
            }

            ## 限制
            - 你是决策辅助角色，只输出建议与待确认方案，绝不声称已执行、已下发或已生效任何信号控制指令。
            - 所有控制与绿波建议一律标注"待后端校验与人工确认"，最终由人工决定是否采纳。
            - 涉及实时数值时必须来自系统传入的上下文，禁止编造路口 ID、指标数值或告警事件。
            - 只处理城市交通信号控制与调度相关问题；与此无关的请求礼貌拒绝并引导回主题。
            - 数据缺失或工具失败时如实说明，不臆测。
            - 表达保持中立、专业、可审计，关键建议须可追溯到指标或知识依据。
            """;

    private final String baseUrl;
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
        this(baseUrl, appId, apiKey, objectMapper, RestClient.builder()
                .baseUrl(removeTrailingSlash(baseUrl))
                .build());
    }

    BailianAgentService(
            String baseUrl,
            String appId,
            String apiKey,
            ObjectMapper objectMapper,
            RestClient restClient
    ) {
        this.baseUrl = removeTrailingSlash(baseUrl);
        this.appId = appId;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        if (!StringUtils.hasText(appId)) {
            log.warn("Bailian agent config fallback: appId missing");
            return configFallback("百炼应用 ID 未配置，请设置 BAILIAN_APP_ID 或 application.yml 中的 bailian.app-id。");
        }
        if (!StringUtils.hasText(apiKey)) {
            log.warn("Bailian agent config fallback: apiKey missing, appId={}", mask(appId));
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

        log.info(
                "Bailian agent request start: appId={}, baseUrl={}, hasSession={}, messageChars={}, contextKeys={}",
                mask(appId),
                baseUrl,
                StringUtils.hasText(request.sessionId()),
                request.message() == null ? 0 : request.message().length(),
                request.context() == null ? "[]" : request.context().keySet()
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/apps/{appId}/completion", appId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(payload)
                    .retrieve()
                    .onStatus(status -> status.isError(), (request_, response_) -> {
                        int statusCode = response_.getStatusCode().value();
                        String responseBody = sanitize(readResponseBody(response_));
                        log.warn(
                                "Bailian agent request failed: status={}, appId={}, baseUrl={}, responseBody={}",
                                statusCode,
                                mask(appId),
                                baseUrl,
                                responseBody
                        );
                        throw new BusinessException(buildStatusMessage(statusCode, responseBody));
                    })
                    .body(JsonNode.class);

            String reply = extractReply(response);
            if (!StringUtils.hasText(reply)) {
                log.warn("Bailian agent empty reply: appId={}, response={}", mask(appId), sanitize(toJson(response)));
                throw new BusinessException("百炼返回为空，请检查应用发布状态、应用 ID、调用权限和响应格式。");
            }

            String newSessionId = extractSessionId(response);
            log.info(
                    "Bailian agent request success: appId={}, replyChars={}, hasSession={}",
                    mask(appId),
                    reply.length(),
                    StringUtils.hasText(newSessionId)
            );
            return new AgentChatResponse(reply, newSessionId, "bailian", false);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.warn(
                    "Bailian agent client error: appId={}, baseUrl={}, errorType={}, message={}",
                    mask(appId),
                    baseUrl,
                    ex.getClass().getSimpleName(),
                    sanitize(ex.getMessage()),
                    ex
            );
            throw new BusinessException("百炼调用失败：网络或客户端异常，请检查后端网络、BAILIAN_BASE_URL、API Key、应用 ID 与百炼服务状态。", ex);
        } catch (RuntimeException ex) {
            log.error("Bailian agent unexpected error: appId={}, baseUrl={}", mask(appId), baseUrl, ex);
            throw new BusinessException("百炼调用失败：后端处理异常，请查看后端日志定位。", ex);
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
            log.debug("Bailian agent context serialization failed: {}", ex.getMessage());
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

    private static String readResponseBody(ClientHttpResponse response) throws IOException {
        byte[] bytes = response.getBody().readAllBytes();
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String buildStatusMessage(int statusCode, String responseBody) {
        String hint = switch (statusCode) {
            case 401 -> "请检查 BAILIAN_API_KEY 是否有效、是否有 DashScope/百炼调用权限，以及 API Key 与应用 ID 是否属于同一账号。";
            case 403 -> "请检查 API Key 权限、应用发布状态、服务开通状态和账号授权。";
            case 404 -> "请检查 BAILIAN_APP_ID、BAILIAN_BASE_URL 和应用是否已发布。";
            case 429 -> "百炼限流或额度不足，请稍后重试并检查账户额度。";
            default -> "请查看后端日志中的百炼响应摘要。";
        };
        String bodyBrief = StringUtils.hasText(responseBody) ? " 响应摘要：" + responseBody : "";
        return "百炼调用失败：HTTP " + statusCode + "。" + hint + bodyBrief;
    }

    private String toJson(JsonNode response) {
        if (response == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            return response.toString();
        }
    }

    private static String sanitize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String sanitized = text
                .replaceAll("sk-[A-Za-z0-9_\\-]+", "sk-***")
                .replaceAll("(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)[^\\s,}]+", "$1***");
        return sanitized.length() <= MAX_ERROR_BODY_CHARS
                ? sanitized
                : sanitized.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }

    private static String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "<empty>";
        }
        if (value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private static String removeTrailingSlash(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://dashscope.aliyuncs.com/api/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
