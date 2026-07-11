package com.traffic.simulation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.runtime.persistence.RuntimePersistenceService;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.session.SimulationSessionRegistry;
import com.traffic.simulation.session.SimulationSessionState;
import com.traffic.simulation.telemetry.SimulationTelemetryService;
import com.traffic.simulation.websocket.SimulationWebSocketHandler;
import com.traffic.strategy.dto.AppliedControlResult;
import com.traffic.strategy.service.StrategyDispatchService;
import com.traffic.strategy.service.TrafficSignalControllerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationServiceTest {

    @Test
    void finishedFrameReleasesBackendSessionWithoutDispatchingAnotherDecision() {
        CityFlowClient cityFlowClient = mock(CityFlowClient.class);
        SimulationSessionRegistry registry = new SimulationSessionRegistry();
        SimulationWebSocketHandler webSocketHandler = new SimulationWebSocketHandler(new ObjectMapper());
        TrafficSignalControllerRegistry controllerRegistry = new TrafficSignalControllerRegistry(List.of());
        AtomicBoolean strategyReleased = new AtomicBoolean();
        StrategyDispatchService strategyDispatchService = new StrategyDispatchService(
                null, controllerRegistry, null, null
        ) {
            @Override
            public AppliedControlResult decideAndApply(
                    com.traffic.simulation.session.SimulationRuntimeSession session,
                    SimFrameData frame
            ) {
                throw new AssertionError("finished frame must not dispatch another decision");
            }

            @Override
            public void releaseSession(String sid) {
                strategyReleased.set(true);
            }
        };
        SimulationFrameTimingLogger frameTimingLogger = new SimulationFrameTimingLogger(new ObjectMapper()) {
            @Override
            public void append(Map<String, Object> payload) {
            }

            @Override
            public boolean shouldWarn(long totalMs) {
                return false;
            }
        };
        AtomicBoolean runtimeFramePersisted = new AtomicBoolean();
        AtomicBoolean runtimeFinished = new AtomicBoolean();
        RuntimePersistenceService runtimePersistenceService = new RuntimePersistenceService(null, new ObjectMapper()) {
            @Override
            public void persistFrame(
                    com.traffic.simulation.session.SimulationRuntimeSession session,
                    long seq,
                    SimFrameData frame,
                    List<com.traffic.strategy.dto.ControlDecision> decisions
            ) {
                runtimeFramePersisted.set(seq == 1L && frame.status().equals("finished") && decisions.isEmpty());
            }

            @Override
            public void updateSessionStatus(String sid, String status) {
                runtimeFinished.set(sid.equals("run_finished") && status.equals("finished"));
            }
        };
        AtomicBoolean telemetryRecorded = new AtomicBoolean();
        AtomicBoolean telemetryFinished = new AtomicBoolean();
        SimulationTelemetryService telemetryService = new SimulationTelemetryService(null, 1000) {
            @Override
            public void recordFrame(
                    com.traffic.simulation.session.SimulationRuntimeSession session,
                    long seq,
                    SimFrameData frame,
                    boolean force
            ) {
                telemetryRecorded.set(seq == 1L && frame.status().equals("finished") && force);
            }

            @Override
            public void markFinished(com.traffic.simulation.session.SimulationRuntimeSession session) {
                telemetryFinished.set(true);
            }
        };
        SimulationService service = new SimulationService(
                cityFlowClient,
                registry,
                webSocketHandler,
                controllerRegistry,
                strategyDispatchService,
                frameTimingLogger,
                runtimePersistenceService,
                telemetryService
        );
        var session = registry.register("run_finished", "jinan_3x4", "fixed-time");
        session.setState(SimulationSessionState.RUNNING);
        SimFrameData finishedFrame = new SimFrameData(
                1800.0,
                "finished",
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of()
        );
        when(cityFlowClient.nextFrame("run_finished")).thenReturn(finishedFrame);

        service.publishNextFrame(session);

        assertTrue(registry.find("run_finished").isEmpty());
        assertTrue(strategyReleased.get());
        assertTrue(runtimeFramePersisted.get());
        assertTrue(runtimeFinished.get());
        assertTrue(telemetryRecorded.get());
        assertTrue(telemetryFinished.get());
    }
}
