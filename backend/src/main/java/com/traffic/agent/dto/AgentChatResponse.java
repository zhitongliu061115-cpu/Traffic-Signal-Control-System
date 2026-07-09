package com.traffic.agent.dto;

public record AgentChatResponse(
        String reply,
        String sessionId,
        String source,
        boolean fallback
) {
}
