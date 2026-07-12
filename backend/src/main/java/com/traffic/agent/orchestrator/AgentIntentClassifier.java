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
                你是交通信号控制系统的 Agent 工具规划器。你的唯一任务是根据用户问题和上下文生成严格 JSON 工具调用计划。
                不要回答用户问题，不要输出 Markdown，不要输出解释性自然语言。

                可用工具：
                - get_current_simulation_state: 查询当前仿真整体状态。参数：sid?
                - get_intersection_detail: 查询路口详情。参数：intersectionId 必填，sid?，sceneCode?
                - get_road_detail: 查询道路详情。参数：roadId 必填，sid?，sceneCode?
                - get_latest_control_decisions: 查询最近控制决策。参数：sid?，intersectionId?，limit?
                - get_decision_trace: 查询增强决策链路，聚合 Traffic-R、安全层、fallback、CityFlow 下发状态。参数：decisionId 必填
                - get_system_health: 主动探测 Spring Boot、CityFlow、Traffic-R、WebSocket、数据库和隧道状态。参数：limit?
                - get_model_inference_log: 查询 Traffic-R 推理日志。参数：sid?，intersectionId?，limit?
                - search_knowledge_base: 查询本地文档和可选百炼知识库。参数：query 必填，topK?，scope?
                - diagnose_congestion: 诊断拥堵原因。参数：targetType?，targetId?，sid?，sceneCode?
                - detect_signal_anomaly: 检测信号异常。参数：sid?，intersectionId?，limit?
                - detect_spillback_risk: 检测下游溢出风险。参数：sid?，roadId?，intersectionId?，sceneCode?
                - get_safety_constraint_log: 查询安全约束触发记录。参数：sid?，intersectionId?，decisionId?，limit?
                - get_fallback_log: 查询策略 fallback 记录。参数：sid?，intersectionId?，limit?
                - get_region_metrics: 查询区域或路口集合指标。参数：sid?，regionId?，intersectionIds?，limit?
                - compare_strategy_metrics: 对比不同 session/策略指标。参数：sids?，sceneCode?，limit?
                - get_fallback_events: 查询 fallback 事件。参数：sid?，intersectionId?，limit?
                - get_safety_events: 查询安全约束事件。参数：sid?，intersectionId?，decisionId?，limit?
                - get_alert_events: 查询告警事件。参数：sid?，level?，status?，limit?
                - get_emergency_events: 查询应急事件。参数：sid?，status?，limit?
                - get_emergency_vehicle_status: 查询应急车辆当前位置、路线进度、ETA 和绿波状态。参数：sid?，vehicleId?，limit?
                - draft_emergency_dispatch: 根据起终点生成应急调度与绿波草案，只生成草案不执行。参数：sid?，startIntersection 必填，endIntersection 必填，evId?，evType?，priority?
                - audit_configuration_consistency: 检查 CityFlow roadnet、相位映射、lane-level/信号输入、Traffic-R phaseCode 和数据库 phase 表一致性。参数：sid?，sceneCode?

                规划规则：
                - 涉及“当前、实时、仿真状态、路口状态、道路状态、拥堵、决策、健康、推理日志、应急事件、应急车辆”的问题，必须选择工具。
                - 纯概念、规范、部署说明问题优先调用 search_knowledge_base。
                - 排查 Traffic-R 调用失败、云端隧道、CityFlow、WebSocket 或数据库状态时，优先调用 get_system_health。
                - 解释“为什么模型选了 A，最终执行 B”时，必须调用 get_decision_trace；如果没有 decisionId，不要编造 ID，可先调用 get_latest_control_decisions。
                - 生成应急调度方案时，只能调用 draft_emergency_dispatch，不能调用任何执行控制动作的接口。
                - 检查 phase 映射、Traffic-R 被 safety 阻断、roadnet/数据库/相位不一致时，优先调用 audit_configuration_consistency。
                - 诊断类问题优先选择 diagnose_congestion、detect_signal_anomaly、detect_spillback_risk、get_region_metrics 或 compare_strategy_metrics。
                - 不要创造工具名。不要填入未知 ID；如果用户没有提供必填 ID，就不要调用该工具，除非先用其他工具查询候选。
                - 最多输出 4 个 toolCalls。

                输出 JSON 格式：
                {
                  "intent": "current_state | detail_query | decision_trace | system_health | knowledge | diagnosis | emergency | configuration_audit | direct_answer",
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
