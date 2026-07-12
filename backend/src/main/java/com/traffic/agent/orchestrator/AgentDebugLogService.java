package com.traffic.agent.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AgentDebugLogService {

    private static final Logger log = LoggerFactory.getLogger("AGENT_DEBUG");
    private static final int MAX_TEXT_CHARS = 4000;

    private final ObjectMapper objectMapper;

    public AgentDebugLogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.findAndRegisterModules();
    }

    public void info(String event, Map<String, Object> fields) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("{} {}", event, toJson(sanitize(fields)));
    }

    public void warn(String event, Map<String, Object> fields) {
        if (!log.isWarnEnabled()) {
            return;
        }
        log.warn("{} {}", event, toJson(sanitize(fields)));
    }

    public void error(String event, Map<String, Object> fields, Throwable throwable) {
        log.error("{} {}", event, toJson(sanitize(fields)), throwable);
    }

    public String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_TEXT_CHARS ? value : value.substring(0, MAX_TEXT_CHARS) + "...<truncated>";
    }

    private Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, rawValue) -> {
                String name = String.valueOf(key);
                sanitized.put(name, isSensitiveKey(name) ? "<redacted>" : sanitize(rawValue));
            });
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }
        if (value instanceof String text) {
            return truncate(text);
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("key")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("authorization");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
