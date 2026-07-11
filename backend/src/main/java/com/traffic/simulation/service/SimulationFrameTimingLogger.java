package com.traffic.simulation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Map;

@Component
public class SimulationFrameTimingLogger {

    private static final Logger log = LoggerFactory.getLogger(SimulationFrameTimingLogger.class);
    private static final long SLOW_FRAME_THRESHOLD_MS = 500;

    private final ObjectMapper objectMapper;
    private final Path logPath = Path.of("logs", "simulation-frame-timing.jsonl");

    public SimulationFrameTimingLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void append(Map<String, Object> payload) {
        try {
            Files.createDirectories(logPath.getParent());
            Map<String, Object> event = Map.of(
                    "event", "backend.simulation-frame.timing",
                    "timestamp", Instant.now().toString(),
                    "payload", payload
            );
            Files.writeString(
                    logPath,
                    objectMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (JsonProcessingException ex) {
            log.warn("failed to serialize simulation frame timing log", ex);
        } catch (IOException ex) {
            log.warn("failed to write simulation frame timing log", ex);
        }
    }

    public boolean shouldWarn(long totalMs) {
        return totalMs >= SLOW_FRAME_THRESHOLD_MS;
    }
}
