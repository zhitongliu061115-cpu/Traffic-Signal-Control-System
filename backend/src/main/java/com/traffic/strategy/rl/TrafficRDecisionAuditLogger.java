package com.traffic.strategy.rl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class TrafficRDecisionAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(TrafficRDecisionAuditLogger.class);

    private final ObjectMapper objectMapper;
    private final Path logPath = Path.of("logs", "traffic-r-decisions.jsonl");

    public TrafficRDecisionAuditLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void append(String event, Object payload) {
        Map<String, Object> record = new HashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("event", event);
        record.put("payload", payload);
        try {
            Files.createDirectories(logPath.getParent());
            String line = objectMapper.writeValueAsString(record) + System.lineSeparator();
            Files.writeString(
                    logPath,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ex) {
            log.warn("failed to append Traffic-R audit log. event={}, path={}, error={}", event, logPath, ex.getMessage());
        }
    }
}
