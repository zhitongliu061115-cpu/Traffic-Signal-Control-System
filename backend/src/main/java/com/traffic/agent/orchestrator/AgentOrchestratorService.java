package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.dto.AgentChatResponse;
import com.traffic.agent.dto.AgentChatResponse.PlanTrace;
import com.traffic.agent.dto.AgentDataDtos.ConversationResponse;
import com.traffic.agent.dto.AgentDataDtos.CreateConversationRequest;
import com.traffic.agent.dto.AgentDataDtos.CreateMessageRequest;
import com.traffic.agent.dto.AgentDataDtos.MessageResponse;
import com.traffic.agent.service.AgentDataService;
import com.traffic.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentOrchestratorService {

    private final AgentDataService agentDataService;
    private final AgentContextBuilder contextBuilder;
    private final AgentIntentClassifier intentClassifier;
    private final AgentToolExecutor toolExecutor;
    private final AgentResponseAssembler responseAssembler;
    private final ObjectMapper objectMapper;

    public AgentOrchestratorService(
            AgentDataService agentDataService,
            AgentContextBuilder contextBuilder,
            AgentIntentClassifier intentClassifier,
            AgentToolExecutor toolExecutor,
            AgentResponseAssembler responseAssembler,
            ObjectMapper objectMapper
    ) {
        this.agentDataService = agentDataService;
        this.contextBuilder = contextBuilder;
        this.intentClassifier = intentClassifier;
        this.toolExecutor = toolExecutor;
        this.responseAssembler = responseAssembler;
        this.objectMapper = objectMapper;
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        AgentContextBuilder.AgentContext context = contextBuilder.build(request);
        ConversationResponse conversation = resolveConversation(request, context);
        MessageResponse userMessage = agentDataService.createMessage(
                conversation.id(),
                new CreateMessageRequest("user", request.message())
        );

        AgentPlan plan = intentClassifier.plan(new AgentIntentClassifier.AgentPlanningInput(
                request.message(),
                context.sid(),
                context.contextJson()
        ));
        recordPlanTrace(userMessage.id(), request, context, plan);

        List<AgentToolExecution> executions = new ArrayList<>();
        if (plan.needsTools()) {
            for (AgentPlan.PlannedToolCall plannedCall : plan.toolCalls()) {
                executions.add(toolExecutor.execute(userMessage.id(), plannedCall));
            }
        }

        AgentResponseAssembler.AgentAnswer answer = responseAssembler.assemble(request, context, plan, executions);
        MessageResponse assistantMessage = agentDataService.createMessage(
                conversation.id(),
                new CreateMessageRequest("assistant", answer.reply())
        );

        return new AgentChatResponse(
                answer.reply(),
                request.sessionId(),
                answer.source(),
                answer.fallback() || plan.fallback(),
                conversation.id(),
                assistantMessage.id(),
                answer.toolCalls(),
                answer.evidence(),
                new PlanTrace(
                        plan.intent(),
                        plan.rationale(),
                        plan.needsTools(),
                        plan.rawPlan(),
                        plan.plannerSource()
                )
        );
    }

    private ConversationResponse resolveConversation(
            AgentChatRequest request,
            AgentContextBuilder.AgentContext context
    ) {
        if (StringUtils.hasText(request.conversationId())) {
            return agentDataService.getConversation(request.conversationId());
        }
        String title = buildConversationTitle(request.message());
        return agentDataService.createConversation(new CreateConversationRequest(
                null,
                context.sid(),
                request.sessionId(),
                title
        ));
    }

    private void recordPlanTrace(
            String messageId,
            AgentChatRequest request,
            AgentContextBuilder.AgentContext context,
            AgentPlan plan
    ) {
        Map<String, Object> arguments = Map.of(
                "message", request.message(),
                "sid", context.sid() == null ? "" : context.sid(),
                "context", context.contextJson()
        );
        Map<String, Object> result = Map.of(
                "intent", plan.intent(),
                "needsTools", plan.needsTools(),
                "rationale", plan.rationale() == null ? "" : plan.rationale(),
                "toolCalls", plan.toolCalls(),
                "rawPlan", plan.rawPlan() == null ? "" : plan.rawPlan(),
                "plannerSource", plan.plannerSource() == null ? "" : plan.plannerSource()
        );
        agentDataService.recordToolCall(
                messageId,
                "llm_tool_plan",
                arguments,
                result,
                plan.fallback() ? "FAILED" : "SUCCESS",
                0,
                plan.fallback() ? "LLM 工具规划未返回可解析 JSON" : null
        );
    }

    private String buildConversationTitle(String message) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException("message不能为空");
        }
        String compact = message.trim().replaceAll("\\s+", " ");
        return compact.length() <= 48 ? compact : compact.substring(0, 48);
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
