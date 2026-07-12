package com.traffic.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public final class AgentDataDtos {

    private AgentDataDtos() {
    }

    public record CreateConversationRequest(
            String userId,
            String sid,
            String externalSessionId,
            @NotBlank(message = "title不能为空")
            @Size(max = 128, message = "title长度不能超过128")
            String title
    ) {
    }

    public record ConversationResponse(
            String id,
            String userId,
            String sid,
            String externalSessionId,
            String title,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CreateMessageRequest(
            @NotBlank(message = "role不能为空")
            @Size(max = 64, message = "role长度不能超过64")
            String role,
            @NotBlank(message = "content不能为空")
            String content
    ) {
    }

    public record MessageResponse(
            String id,
            String conversationId,
            String role,
            String content,
            Instant createdAt
    ) {
    }

    public record RecordToolCallRequest(
            @NotBlank(message = "toolName不能为空")
            @Size(max = 128, message = "toolName长度不能超过128")
            String toolName,
            Map<String, Object> arguments,
            Object result,
            @Size(max = 64, message = "status长度不能超过64")
            String status,
            Integer latencyMs,
            String errorMessage
    ) {
    }

    public record ToolCallResponse(
            String id,
            String messageId,
            String toolName,
            String argumentsPayload,
            String resultPayload,
            String status,
            int latencyMs,
            String errorMessage,
            Instant createdAt
    ) {
    }
}
