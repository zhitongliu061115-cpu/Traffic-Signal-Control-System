package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentIntentClassifier {

    private static final int MAX_TOOL_CALLS = 4;

    private final AgentLlmClient llmClient;
    private final AgentToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public AgentIntentClassifier(AgentLlmClient llmClient, AgentToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    public AgentPlan plan(AgentPlanningInput input) {
        AgentLlmClient.LlmResult llmResult = llmClient.chat("tool_plan", plannerSystemPrompt(), plannerUserPrompt(input));
        String rawPlan = llmResult.text();
        try {
            AgentPlan parsed = parsePlan(rawPlan);
            return parsed.withRawPlan(rawPlan, llmResult.source(), llmResult.fallback());
        } catch (Exception ex) {
            return AgentPlan.directAnswer(
                    "LLM 未返回可解析的工具调用 JSON，后端不会猜测工具调用。",
                    rawPlan,
                    llmResult.source(),
                    true
            );
        }
    }

    private AgentPlan parsePlan(String rawPlan) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(rawPlan));
        String intent = text(root, "intent", "unknown");
        boolean needsTools = root.path("needsTools").asBoolean(false);
        String rationale = text(root, "rationale", "");
        List<AgentPlan.PlannedToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = root.path("toolCalls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                if (toolCalls.size() >= MAX_TOOL_CALLS) {
                    break;
                }
                String toolName = text(call, "toolName", "");
                if (!toolExecutor.allowedTools().contains(toolName)) {
                    continue;
                }
                Map<String, Object> arguments = objectMapper.convertValue(
                        call.path("arguments").isMissingNode() ? objectMapper.createObjectNode() : call.path("arguments"),
                        objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
                );
                toolCalls.add(new AgentPlan.PlannedToolCall(
                        toolName,
                        arguments,
                        text(call, "reason", "")
                ));
            }
        }
        return new AgentPlan(intent, needsTools && !toolCalls.isEmpty(), rationale, toolCalls, rawPlan, "llm", false);
    }

    private String extractJson(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "{}";
        }
        String text = rawText.trim();
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = text.indexOf('\n', fenceStart);
            int fenceEnd = text.indexOf("```", Math.max(contentStart, fenceStart + 3));
            if (contentStart >= 0 && fenceEnd > contentStart) {
                text = text.substring(contentStart + 1, fenceEnd).trim();
            }
        }
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1);
        }
        return text;
    }

    private String plannerSystemPrompt() {
        return """
                你是交通信号控制系统的 Agent 工具规划器。你的唯一任务是根据用户问题和上下文，生成严格 JSON 工具调用计划。
                不要回答用户问题，不要输出 Markdown，不要输出解释性自然语言。

                可用工具：
                - get_current_simulation_state: 查询当前仿真整体状态。参数：sid?
                - get_intersection_detail: 查询路口详情。参数：intersectionId 必填，sid?，sceneCode?
                - get_road_detail: 查询道路详情。参数：roadId 必填，sid?，sceneCode?
                - get_latest_control_decisions: 查询最近控制决策。参数：sid?，intersectionId?，limit?
                - get_decision_trace: 查询指定决策链路。参数：decisionId 必填
                - get_system_health: 查询系统健康。参数：limit?
                - get_model_inference_log: 查询 Traffic-R 推理日志。参数：sid?，intersectionId?，limit?
                - search_knowledge_base: 查询项目文档、接口规范、部署资料、Agent 设计和算法说明。参数：query 必填，topK?，scope?
                - diagnose_congestion: 基于真实快照和决策证据诊断拥堵原因。参数：targetType?，targetId?，sid?，sceneCode?
                - detect_signal_anomaly: 检测信号异常、相位长时间不变、安全约束触发或相位映射疑似异常。参数：sid?，intersectionId?，limit?
                - detect_spillback_risk: 检测道路或路口下游溢出风险。参数：sid?，roadId?，intersectionId?，sceneCode?
                - get_safety_constraint_log: 查询安全约束触发记录。参数：sid?，intersectionId?，decisionId?，limit?
                - get_fallback_log: 查询策略降级/fallback 记录。参数：sid?，intersectionId?，limit?
                - get_region_metrics: 查询区域或路口集合指标。参数：sid?，regionId?，intersectionIds?，limit?
                - compare_strategy_metrics: 对比不同 session/策略的效果指标。参数：sids?，sceneCode?，limit?
                - get_fallback_events: 查询策略降级事件。参数：sid?，intersectionId?，limit?
                - get_safety_events: 查询安全约束事件。参数：sid?，intersectionId?，decisionId?，limit?
                - get_alert_events: 查询告警事件。参数：sid?，level?，status?，limit?
                - get_emergency_events: 查询应急事件。参数：sid?，status?，limit?

                规划规则：
                - 涉及“当前、实时、仿真状态、路口状态、道路状态、拥堵、决策、健康、推理日志、应急事件”的问题，必须选择工具。
                - 纯概念、纯规范、纯部署说明问题优先调用 search_knowledge_base。
                - 用户要求“诊断、分析原因、为什么堵、是否异常、是否溢出、策略效果对比”时，优先选择 diagnose_congestion、detect_signal_anomaly、detect_spillback_risk、get_region_metrics 或 compare_strategy_metrics。
                - 诊断类工具会返回结论、证据、影响范围、可能原因、建议动作、置信度和需要人工确认事项；不要用普通查询工具替代诊断工具。
                - 不要创造工具名。不要填入未知 ID；如果用户没有提供必填 ID，就不要调用该工具。
                - 最多输出 4 个 toolCalls。

                输出 JSON 格式：
                {
                  "intent": "current_state | detail_query | decision_trace | system_health | knowledge | diagnosis | direct_answer",
                  "needsTools": true,
                  "rationale": "为什么需要或不需要工具",
                  "toolCalls": [
                    {
                      "toolName": "get_current_simulation_state",
                      "arguments": {"sid": "可选"},
                      "reason": "调用原因"
                    }
                  ]
                }
                """;
    }

    private String plannerUserPrompt(AgentPlanningInput input) {
        return "用户问题：\n" + input.message()
                + "\n\n可用上下文 JSON：\n" + input.contextJson()
                + "\n\n当前仿真 sid：\n" + nullToEmpty(input.sid())
                + "\n\n请只输出 JSON。";
    }

    private String text(JsonNode node, String field, String defaultValue) {
        String value = node.path(field).asText(null);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record AgentPlanningInput(
            String message,
            String sid,
            String contextJson
    ) {
    }
}
