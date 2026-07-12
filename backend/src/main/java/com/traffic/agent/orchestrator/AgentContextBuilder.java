package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentChatRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentContextBuilder {

    private static final int MAX_CONTEXT_CHARS = 4000;

    private final ObjectMapper objectMapper;

    public AgentContextBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
