package com.traffic.agent.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentEmergencyDispatchMemory {

    private final Map<String, DispatchEndpoints> latestBySid = new ConcurrentHashMap<>();

    public void remember(
            String sid,
            String startIntersection,
            String endIntersection,
            String evId,
            String evType,
            Integer priority
    ) {
        if (!StringUtils.hasText(sid)
                || !StringUtils.hasText(startIntersection)
                || !StringUtils.hasText(endIntersection)) {
            return;
        }
        latestBySid.put(sid.trim(), new DispatchEndpoints(
                sid.trim(),
                startIntersection.trim(),
                endIntersection.trim(),
                blankToNull(evId),
                blankToNull(evType),
                priority,
                Instant.now()
        ));
    }

    public Optional<DispatchEndpoints> latest(String sid) {
        if (!StringUtils.hasText(sid)) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestBySid.get(sid.trim()));
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record DispatchEndpoints(
            String sid,
            String startIntersection,
            String endIntersection,
            String evId,
            String evType,
            Integer priority,
            Instant updatedAt
    ) {
    }
}
