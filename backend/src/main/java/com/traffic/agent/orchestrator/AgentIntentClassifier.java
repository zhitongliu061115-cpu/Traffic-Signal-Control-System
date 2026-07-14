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
            AgentPlan parsed = normalizePlan(parsePlan(rawPlan), input);
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

    private AgentPlan normalizePlan(AgentPlan plan, AgentPlanningInput input) {
        DispatchMemory dispatchMemory = dispatchMemoryFromContext(input.contextJson());
        List<AgentPlan.PlannedToolCall> normalizedCalls = new ArrayList<>();
        for (AgentPlan.PlannedToolCall call : plan.toolCalls()) {
            AgentPlan.PlannedToolCall normalized = normalizeRealtimeToolChoice(call, input);
            normalized = normalizeEmergencyDraftFromMemory(normalized, dispatchMemory, input);
            if (hasInvalidRequiredArguments(normalized)) {
                continue;
            }
            normalizedCalls.add(normalized);
        }
        if (normalizedCalls.isEmpty()) {
            String rationale = plan.rationale();
            if (looksLikeEmergencyDraft(input.message()) && dispatchMemory != null) {
                return new AgentPlan(
                        plan.intent(),
                        true,
                        appendRationale(rationale, "已读取最近一次应急调度起终点，自动调用 draft_emergency_dispatch 生成草案。"),
                        List.of(emergencyDraftCall(dispatchMemory, input.sid(), "使用最近一次应急调度起终点生成调度草案")),
                        plan.rawPlan(),
                        plan.plannerSource(),
                        plan.fallback()
                );
            }
            if (looksLikeEmergencyDraft(input.message())) {
                rationale = appendRationale(rationale, "生成应急调度草案需要真实起点和终点路口 ID，当前请求未提供，后端已阻止占位参数调用。");
            } else if (plan.needsTools()) {
                rationale = appendRationale(rationale, "工具计划包含缺失或占位参数，后端已阻止执行。");
            }
            return new AgentPlan(plan.intent(), false, rationale, List.of(), plan.rawPlan(), plan.plannerSource(), plan.fallback());
        }
        return new AgentPlan(plan.intent(), plan.needsTools(), plan.rationale(), normalizedCalls, plan.rawPlan(), plan.plannerSource(), plan.fallback());
    }

    private AgentPlan.PlannedToolCall normalizeEmergencyDraftFromMemory(
            AgentPlan.PlannedToolCall call,
            DispatchMemory dispatchMemory,
            AgentPlanningInput input
    ) {
        if (!"draft_emergency_dispatch".equals(call.toolName()) || dispatchMemory == null) {
            return call;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (call.arguments() != null) {
            arguments.putAll(call.arguments());
        }
        if (!StringUtils.hasText(String.valueOf(arguments.getOrDefault("sid", ""))) && StringUtils.hasText(input.sid())) {
            arguments.put("sid", input.sid());
        }
        if (invalidArg(arguments, "startIntersection")) {
            arguments.put("startIntersection", dispatchMemory.startIntersection());
        }
        if (invalidArg(arguments, "endIntersection")) {
            arguments.put("endIntersection", dispatchMemory.endIntersection());
        }
        if (!arguments.containsKey("evId") && StringUtils.hasText(dispatchMemory.evId())) {
            arguments.put("evId", dispatchMemory.evId());
        }
        if (!arguments.containsKey("evType") && StringUtils.hasText(dispatchMemory.evType())) {
            arguments.put("evType", dispatchMemory.evType());
        }
        if (!arguments.containsKey("priority") && dispatchMemory.priority() != null) {
            arguments.put("priority", dispatchMemory.priority());
        }
        return new AgentPlan.PlannedToolCall(
                call.toolName(),
                arguments,
                appendRationale(call.reason(), "已从最近一次应急调度记忆补齐起终点路口。")
        );
    }

    private AgentPlan.PlannedToolCall emergencyDraftCall(DispatchMemory dispatchMemory, String sid, String reason) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (StringUtils.hasText(sid)) {
            arguments.put("sid", sid);
        }
        arguments.put("startIntersection", dispatchMemory.startIntersection());
        arguments.put("endIntersection", dispatchMemory.endIntersection());
        if (StringUtils.hasText(dispatchMemory.evId())) {
            arguments.put("evId", dispatchMemory.evId());
        }
        if (StringUtils.hasText(dispatchMemory.evType())) {
            arguments.put("evType", dispatchMemory.evType());
        }
        if (dispatchMemory.priority() != null) {
            arguments.put("priority", dispatchMemory.priority());
        }
        return new AgentPlan.PlannedToolCall("draft_emergency_dispatch", arguments, reason);
    }

    private AgentPlan.PlannedToolCall normalizeRealtimeToolChoice(
            AgentPlan.PlannedToolCall call,
            AgentPlanningInput input
    ) {
        if (looksLikeRealtimeCongestionQuestion(input.message()) && shouldUseNetworkCongestionDiagnosis(call.toolName())) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (StringUtils.hasText(input.sid())) {
                arguments.put("sid", input.sid());
            }
            return new AgentPlan.PlannedToolCall(
                    "diagnose_congestion",
                    arguments,
                    appendRationale(call.reason(), "实时拥堵问题应读取内存最新帧，因此改用 diagnose_congestion。")
            );
        }
        return call;
    }

    private boolean shouldUseNetworkCongestionDiagnosis(String toolName) {
        return "get_region_metrics".equals(toolName)
                || "get_intersection_detail".equals(toolName)
                || "get_road_detail".equals(toolName)
                || "diagnose_congestion".equals(toolName);
    }

    private boolean hasInvalidRequiredArguments(AgentPlan.PlannedToolCall call) {
        return switch (call.toolName()) {
            case "get_intersection_detail" -> invalidArg(call.arguments(), "intersectionId");
            case "get_road_detail" -> invalidArg(call.arguments(), "roadId");
            case "get_decision_trace" -> invalidArg(call.arguments(), "decisionId");
            case "search_knowledge_base" -> invalidArg(call.arguments(), "query");
            case "draft_emergency_dispatch" ->
                    invalidArg(call.arguments(), "startIntersection") || invalidArg(call.arguments(), "endIntersection");
            default -> false;
        };
    }

    private boolean invalidArg(Map<String, Object> arguments, String name) {
        if (arguments == null) {
            return true;
        }
        Object value = arguments.get(name);
        if (value == null) {
            return true;
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            return true;
        }
        return List.of(
                "待用户提供",
                "用户提供",
                "未提供",
                "未知",
                "unknown",
                "undefined",
                "null",
                "none",
                "todo",
                "tbd",
                "起点",
                "终点",
                "示例",
                "example",
                "xxx",
                "路口A",
                "路口B",
                "A",
                "B"
        ).stream().anyMatch(placeholder -> placeholder.equalsIgnoreCase(text));
    }

    private DispatchMemory dispatchMemoryFromContext(String contextJson) {
        if (!StringUtils.hasText(contextJson)) {
            return null;
        }
        try {
            JsonNode memory = objectMapper.readTree(contextJson).path("emergencyDispatchMemory");
            if (memory.isMissingNode() || memory.isNull()) {
                return null;
            }
            String start = text(memory, "startIntersection", "");
            String end = text(memory, "endIntersection", "");
            if (!StringUtils.hasText(start) || !StringUtils.hasText(end)) {
                return null;
            }
            return new DispatchMemory(
                    start,
                    end,
                    text(memory, "evId", ""),
                    text(memory, "evType", ""),
                    memory.path("priority").isNumber() ? memory.path("priority").asInt() : null
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean looksLikeRealtimeCongestionQuestion(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return containsAny(message, "当前", "实时", "现在", "最新", "哪个路口最拥堵", "最堵", "拥堵排名");
    }

    private boolean looksLikeEmergencyDraft(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return containsAny(message, "调度建议", "应急调度", "绿波", "救护车", "消防车", "警车");
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String appendRationale(String original, String addition) {
        if (!StringUtils.hasText(original)) {
            return addition;
        }
        return original + "；" + addition;
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
                - 用户只说“生成调度建议/调度建议”但没有给出明确起点和终点时，不要调用 draft_emergency_dispatch；应设置 needsTools=false，并在 rationale 中说明需要用户提供 startIntersection 和 endIntersection。
                - 只有用户明确给出真实起点和终点路口 ID 时，才能调用 draft_emergency_dispatch；不能调用任何执行控制动作的接口。
                - 检查 phase 映射、Traffic-R 被 safety 阻断、roadnet/数据库/相位不一致时，优先调用 audit_configuration_consistency。
                - “当前/实时/现在/哪个路口最拥堵/最堵/拥堵排名”优先选择 diagnose_congestion 或 get_current_simulation_state，不能优先选择 get_region_metrics。
                - get_region_metrics 只用于历史复盘、区域平均指标、明确 regionId/intersectionIds 的低频统计或策略效果汇总；不要把它当作实时状态工具。
                - 诊断类问题优先选择 diagnose_congestion、detect_signal_anomaly、detect_spillback_risk；历史区域统计才选择 get_region_metrics 或 compare_strategy_metrics。
                - 不要创造工具名。不要填入未知 ID；禁止使用“待用户提供”“A”“B”“起点”“终点”“unknown”等占位值。如果用户没有提供必填 ID，就不要调用该工具，除非先用其他工具查询候选。
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

    private record DispatchMemory(
            String startIntersection,
            String endIntersection,
            String evId,
            String evType,
            Integer priority
    ) {
    }
}
