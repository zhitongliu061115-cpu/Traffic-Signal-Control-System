package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse.EvidenceItem;
import com.traffic.agent.dto.AgentChatResponse.ToolCallSummary;
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
        AgentLlmClient.LlmResult result = llmClient.chat(answerSystemPrompt(), answerUserPrompt(request, context, plan, executions));
        String answer = result.text();
        if (answer == null || answer.isBlank()) {
            answer = fallbackAnswer(plan, executions);
        }
        return new AgentAnswer(answer, result.source(), result.fallback(), evidence(executions), toolSummaries(executions));
    }

    private String answerSystemPrompt() {
        return """
                你是城市交通信号调度辅助 Agent。你必须基于后端提供的工具结果回答。

                强制规则：
                - 涉及实时状态、当前仿真、路口/道路状态、控制决策、系统健康、推理日志的问题，只能引用工具结果。
                - 如果规划显示需要工具，但没有成功工具结果，必须明确说明“暂时无法获取真实数据”，不能编造数值、路口 ID、车辆数或相位。
                - 控制类、策略切换类、应急绿波类内容只能给建议或草案，不能声称已经执行。
                - 回答要先给结论，再列关键证据和下一步建议。
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
                + "\n\n请根据以上信息回答用户。";
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
        return executions.stream()
                .filter(AgentToolExecution::success)
                .map(execution -> new EvidenceItem(
                        "tool",
                        execution.toolName(),
                        "工具 " + execution.toolName() + " 返回真实后端数据",
                        execution.result()
                ))
                .toList();
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

    public record AgentAnswer(
            String reply,
            String source,
            boolean fallback,
            List<EvidenceItem> evidence,
            List<ToolCallSummary> toolCalls
    ) {
    }
}
