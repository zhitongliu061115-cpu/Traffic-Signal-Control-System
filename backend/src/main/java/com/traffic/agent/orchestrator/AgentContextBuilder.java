package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import com.traffic.agent.service.AgentEmergencyDispatchMemory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentContextBuilder {

    private static final int MAX_CONTEXT_CHARS = 4000;

    private final ObjectMapper objectMapper;
    private final AgentEmergencyDispatchMemory emergencyDispatchMemory;

    public AgentContextBuilder(ObjectMapper objectMapper, AgentEmergencyDispatchMemory emergencyDispatchMemory) {
        this.objectMapper = objectMapper;
        this.emergencyDispatchMemory = emergencyDispatchMemory;
    }

    public AgentContext build(AgentChatRequest request) {
        String sid = firstText(request.sid(), stringFromContext(request.context(), "sid"));
        Map<String, Object> context = new LinkedHashMap<>();
        if (StringUtils.hasText(sid)) {
            context.put("sid", sid);
        }
        if (StringUtils.hasText(request.conversationId())) {
            context.put("conversationId", request.conversationId());
        }
        emergencyDispatchMemory.latest(sid)
                .ifPresent(memory -> context.put("emergencyDispatchMemory", emergencyDispatchMemoryContext(memory)));
        context.put("contextPolicy", "前端 context 只允许作为路由/会话辅助信息，不能作为实时交通证据。");
        context.put("realtimeDataPolicy", "实时交通状态必须来自后端工具结果；没有成功工具结果时不得编造或引用前端看板指标。");
        return new AgentContext(sid, truncate(toJson(context)));
    }

    private String stringFromContext(Map<String, Object> context, String key) {
        if (context == null || !context.containsKey(key) || context.get(key) == null) {
            return null;
        }
        return String.valueOf(context.get(key));
    }

    private Map<String, Object> emergencyDispatchMemoryContext(AgentEmergencyDispatchMemory.DispatchEndpoints memory) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sid", memory.sid());
        value.put("startIntersection", memory.startIntersection());
        value.put("endIntersection", memory.endIntersection());
        if (StringUtils.hasText(memory.evId())) {
            value.put("evId", memory.evId());
        }
        if (StringUtils.hasText(memory.evType())) {
            value.put("evType", memory.evType());
        }
        if (memory.priority() != null) {
            value.put("priority", memory.priority());
        }
        if (memory.updatedAt() != null) {
            value.put("updatedAt", memory.updatedAt().toString());
        }
        return value;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String truncate(String text) {
        return text.length() <= MAX_CONTEXT_CHARS ? text : text.substring(0, MAX_CONTEXT_CHARS) + "...";
    }

    public record AgentContext(
            String sid,
            String contextJson
    ) {
    }
}
