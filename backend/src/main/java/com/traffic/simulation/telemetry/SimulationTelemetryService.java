package com.traffic.simulation.telemetry;

import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.session.SimulationRuntimeSession;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SimulationTelemetryService {

    private final SimulationTelemetryRepository repository;
    private final long sampleIntervalMs;

    public SimulationTelemetryService(
            SimulationTelemetryRepository repository,
            @Value("${traffic.telemetry.sample-interval-ms:1000}") long sampleIntervalMs
    ) {
        this.repository = repository;
        this.sampleIntervalMs = Math.max(100, sampleIntervalMs);
    }

    public UUID createRun(String sid, String sceneId, String controllerType, Double speed) {
        return repository.createRun(sid, sceneId, controllerType, speed);
    }

    public void markStarted(SimulationRuntimeSession session) {
        repository.markStarted(session.getTelemetryRunId());
    }

    public void markPaused(SimulationRuntimeSession session) {
        repository.markPaused(session.getTelemetryRunId());
    }

    public void markFinished(SimulationRuntimeSession session) {
        repository.markFinished(session.getTelemetryRunId());
    }

    public void recordFrame(SimulationRuntimeSession session, long seq, SimFrameData frame, boolean force) {
        if (session.claimTelemetrySample(System.currentTimeMillis(), sampleIntervalMs, force)) {
            repository.saveFrame(session.getTelemetryRunId(), seq, frame);
        }
    }
}
