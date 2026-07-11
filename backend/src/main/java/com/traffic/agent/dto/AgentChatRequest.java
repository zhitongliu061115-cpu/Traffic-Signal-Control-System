package com.traffic.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record AgentChatRequest(
        @NotBlank(message = "message不能为空")
        @Size(max = 4000, message = "message长度不能超过4000")
        String message,
        String sessionId,
        Map<String, Object> context
) {
}
