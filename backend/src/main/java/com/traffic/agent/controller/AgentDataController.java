package com.traffic.agent.controller;

import com.traffic.agent.dto.AgentDataDtos.ConversationResponse;
import com.traffic.agent.dto.AgentDataDtos.CreateConversationRequest;
import com.traffic.agent.dto.AgentDataDtos.CreateMessageRequest;
import com.traffic.agent.dto.AgentDataDtos.MessageResponse;
import com.traffic.agent.dto.AgentDataDtos.RecordToolCallRequest;
import com.traffic.agent.dto.AgentDataDtos.ToolCallResponse;
import com.traffic.agent.service.AgentDataService;
import com.traffic.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentDataController {

    private final AgentDataService agentDataService;

    public AgentDataController(AgentDataService agentDataService) {
        this.agentDataService = agentDataService;
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationResponse> createConversation(
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return ApiResponse.ok(agentDataService.createConversation(request));
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationResponse>> listConversations(
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String externalSessionId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(agentDataService.listConversations(sid, externalSessionId, userId, limit));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<ConversationResponse> getConversation(@PathVariable String conversationId) {
        return ApiResponse.ok(agentDataService.getConversation(conversationId));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ApiResponse<MessageResponse> createMessage(
            @PathVariable String conversationId,
            @Valid @RequestBody CreateMessageRequest request
    ) {
        return ApiResponse.ok(agentDataService.createMessage(conversationId, request));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<List<MessageResponse>> listMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(agentDataService.listMessages(conversationId, limit));
    }

    @PostMapping("/messages/{messageId}/tool-calls")
    public ApiResponse<ToolCallResponse> recordToolCall(
            @PathVariable String messageId,
            @Valid @RequestBody RecordToolCallRequest request
    ) {
        return ApiResponse.ok(agentDataService.recordToolCall(messageId, request));
    }

    @GetMapping("/messages/{messageId}/tool-calls")
    public ApiResponse<List<ToolCallResponse>> listToolCallsByMessage(
            @PathVariable String messageId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(agentDataService.listToolCallsByMessage(messageId, limit));
    }

    @GetMapping("/tool-calls")
    public ApiResponse<List<ToolCallResponse>> listToolCalls(
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(agentDataService.listToolCalls(toolName, status, limit));
    }
}
