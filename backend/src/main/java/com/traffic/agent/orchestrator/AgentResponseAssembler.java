package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse.EvidenceItem;
import com.traffic.agent.dto.AgentChatResponse.ToolCallSummary;
import com.traffic.agent.tool.AgentToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgentResponseAssembler {

    private static final int MAX_TOOL_RESULT_CHARS = 12000;

    private final AgentLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public AgentResponseAssembler(AgentLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public AgentAnswer assemble(
            AgentChatRequest request,
            AgentContextBuilder.AgentContext context,
            AgentPlan plan,
            List<AgentToolExecution> executions
    ) {
        AgentLlmClient.LlmResult result = llmClient.chat(
                "answer",
                answerSystemPrompt(),
                answerUserPrompt(request, context, plan, executions)
        );
        String answer = result.text();
        if (answer == null || answer.isBlank()) {
            answer = fallbackAnswer(plan, executions);
        }
        answer = sanitizeFinalAnswer(answer);
        return new AgentAnswer(answer, result.source(), result.fallback(), evidence(executions), toolSummaries(executions));
    }

    private String answerSystemPrompt() {
        return """
                你是城市交通信号调度辅助 Agent。你必须基于后端提供的工具结果回答。

                强制规则：
                - 涉及实时状态、当前仿真、路口/道路状态、控制决策、系统健康、推理日志的问题，只能引用工具结果。
                - 如果规划显示需要工具，但没有成功工具结果，必须明确说明“暂时无法获取真实数据”，不能编造数值、路口 ID、车辆数或相位。
                - 控制类、策略切换类、应急绿波类内容只能给建议或草案，不能声称已经执行。
                - 回答要先给结论，再列关键证据和下一步建议；知识库/规范解释类问题至少给 3 个要点，每个要点 1-2 句，除非工具证据不足。
                - 多个要点必须使用 Markdown 编号列表或短横线列表；每个编号/项目必须独立换行，不能把“1. 2. 3.”挤在同一段。
                - 关键标准号、协议名、策略名、路口/道路 ID 或诊断结论要用 Markdown 加粗，例如 **GB/T 39900-2021**、**Traffic-R**。
                - 最终回复必须是面向用户的中文自然语言，只输出最终结论/调度建议，不输出推理过程、工具过程或原始证据对象。
                - 禁止输出 JSON，禁止输出 intent、responseType、content、evidenceList、actionPlan、toolCalls、planTrace 等前端结构化字段。
                - 不要引用前端看板/演示态指标作为事实，例如总流量、拥堵指数、AI优化路口数、应急车辆数、AI效果对比等，除非这些数值明确出现在成功工具结果中。
                - 不要暴露 API Key、数据库密码、认证头或内部堆栈。
                """;
    }

    private String answerUserPrompt(
            AgentChatRequest request,
            AgentContextBuilder.AgentContext context,
            AgentPlan plan,
            List<AgentToolExecution> executions
    ) {
        return "用户问题：\n" + request.message()
                + "\n\n上下文：\n" + context.contextJson()
                + "\n\nLLM 工具规划：\n" + safeJson(Map.of(
                "intent", plan.intent(),
                "needsTools", plan.needsTools(),
                "rationale", plan.rationale(),
                "toolCalls", plan.toolCalls()
        ))
                + "\n\n后端工具执行结果：\n" + truncate(safeJson(executions))
                + "\n\n请根据以上信息回答用户。知识库问题请避免一句话带过，要把关键条目分点展开。";
    }

    private String fallbackAnswer(AgentPlan plan, List<AgentToolExecution> executions) {
        if (plan.needsTools() && executions.stream().noneMatch(AgentToolExecution::success)) {
            return "暂时无法获取真实系统数据，因此不能对当前交通状态作出判断。请检查工具调用、数据库连接或仿真会话是否可用。";
        }
        return "当前 Agent 未能生成有效回答，请查看工具调用结果和后端日志。";
    }

    private List<ToolCallSummary> toolSummaries(List<AgentToolExecution> executions) {
        return executions.stream()
                .map(execution -> new ToolCallSummary(
                        execution.auditId(),
                        execution.toolName(),
                        execution.arguments(),
                        execution.status(),
                        execution.latencyMs(),
                        execution.errorMessage()
                ))
                .toList();
    }

    private List<EvidenceItem> evidence(List<AgentToolExecution> executions) {
        List<EvidenceItem> items = new ArrayList<>();
        for (AgentToolExecution execution : executions) {
            if (!execution.success()) {
                continue;
            }
            if (execution.result() instanceof AgentToolResult toolResult && toolResult.evidence() != null) {
                toolResult.evidence().forEach(evidence -> items.add(new EvidenceItem(
                        evidence.source(),
                        evidence.name(),
                        evidence.summary(),
                        evidence.value()
                )));
            } else {
                items.add(new EvidenceItem(
                        "tool",
                        execution.toolName(),
                        "工具 " + execution.toolName() + " 返回真实后端数据",
                        execution.result()
                ));
            }
        }
        return items;
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String truncate(String text) {
        return text.length() <= MAX_TOOL_RESULT_CHARS ? text : text.substring(0, MAX_TOOL_RESULT_CHARS) + "...";
    }

    private String sanitizeFinalAnswer(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return fallbackAnswer(AgentPlan.directAnswer("", "", "sanitizer", true), List.of());
        }
        String text = stripCodeFence(rawAnswer.trim());
        JsonNode root = tryParseJson(text);
        if (root == null) {
            return normalizeFinalLayout(removeJsonBlocks(removeProcessPrefixes(text)));
        }
        String extracted = firstText(
                textAt(root, "finalDecision"),
                textAt(root, "decision"),
                textAt(root, "recommendation"),
                textAt(root, "conclusion"),
                textAt(root, "summary"),
                textAt(root, "answer"),
                textAt(root, "reply"),
                textAt(root, "content.finalDecision"),
                textAt(root, "content.decision"),
                textAt(root, "content.recommendation"),
                textAt(root, "content.conclusion"),
                textAt(root, "content.summary"),
                textAt(root, "content.answer")
        );
        if (extracted != null && !extracted.isBlank()) {
            return normalizeFinalLayout(removeJsonBlocks(removeProcessPrefixes(extracted.trim())));
        }
        String actionText = actionPlanAsFinalSuggestion(root.path("actionPlan"));
        if (actionText != null) {
            return normalizeFinalLayout(actionText);
        }
        actionText = actionPlanAsFinalSuggestion(root.path("content").path("actionPlan"));
        if (actionText != null) {
            return normalizeFinalLayout(actionText);
        }
        return "已完成分析，但模型返回了结构化过程数据。请重新提问或查看 Agent 工具调用日志获取详情。";
    }

    private String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        int fenceEnd = text.lastIndexOf("```");
        if (firstLineEnd >= 0 && fenceEnd > firstLineEnd) {
            return text.substring(firstLineEnd + 1, fenceEnd).trim();
        }
        return text;
    }

    private JsonNode tryParseJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String textAt(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.isValueNode() ? node.asText() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String actionPlanAsFinalSuggestion(JsonNode actionPlan) {
        if (!actionPlan.isArray() || actionPlan.isEmpty()) {
            return null;
        }
        List<String> suggestions = new ArrayList<>();
        for (JsonNode item : actionPlan) {
            String title = textAt(item, "title");
            String details = textAt(item, "details");
            String text = firstText(details, title);
            if (text != null && !text.isBlank()) {
                suggestions.add(text.trim());
            }
            if (suggestions.size() >= 3) {
                break;
            }
        }
        if (suggestions.isEmpty()) {
            return null;
        }
        return "建议：" + String.join("；", suggestions);
    }

    private String removeProcessPrefixes(String text) {
        return text
                .replaceAll("(?im)^\\s*(evidenceList|actionPlan|toolCalls|planTrace|rawPlan)\\s*[:：].*$", "")
                .trim();
    }

    private String removeJsonBlocks(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String cleaned = text
                .replaceAll("(?is)```json\\s*\\{.*?}\\s*```", "")
                .replaceAll("(?is)```\\s*\\{.*?}\\s*```", "")
                .replaceAll("(?is)^\\s*\\{\\s*\"(intent|responseType|content|evidenceList|actionPlan|toolCalls|planTrace)\".*}\\s*$",
                        "已完成分析，但模型返回了结构化过程数据。请重新提问或查看 Agent 工具日志。")
                .trim();
        if (cleaned.isBlank()) {
            return "已完成分析，但模型返回了结构化过程数据。请重新提问或查看 Agent 工具日志。";
        }
        return cleaned;
    }

    private String normalizeFinalLayout(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\\n", "\n")
                .replace('\u00A0', ' ')
                .replace('\u3000', ' ')
                .replaceAll("[ \\t]+\\n", "\n")
                .trim();
        normalized = normalized.replaceAll("([：:])\\s+(?=\\d+[.、]\\s*)", "$1\n");
        normalized = normalized.replaceAll("(?<!^)(?<!\\n)(?<!\\d)([ \\t\\p{Zs}]+)(?=\\d+[.、]\\s*\\S)", "\n");
        normalized = normalized.replaceAll("(?<!^)(?<!\\n)([ \\t\\p{Zs}]+)(?=[-•]\\s+\\S)", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    public record AgentAnswer(
            String reply,
            String source,
            boolean fallback,
            List<EvidenceItem> evidence,
            List<ToolCallSummary> toolCalls
    ) {
    }
}
