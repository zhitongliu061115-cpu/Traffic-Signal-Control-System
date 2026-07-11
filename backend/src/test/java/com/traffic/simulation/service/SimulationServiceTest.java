package com.traffic.simulation.service;

import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.session.SimulationSessionRegistry;
import com.traffic.simulation.session.SimulationSessionState;
import com.traffic.simulation.websocket.SimulationWebSocketHandler;
import com.traffic.strategy.service.StrategyDispatchService;
import com.traffic.strategy.service.TrafficSignalControllerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationServiceTest {

    @Test
    void finishedFrameReleasesBackendSessionWithoutDispatchingAnotherDecision() {
        CityFlowClient cityFlowClient = mock(CityFlowClient.class);
        SimulationSessionRegistry registry = new SimulationSessionRegistry();
        SimulationWebSocketHandler webSocketHandler = mock(SimulationWebSocketHandler.class);
        TrafficSignalControllerRegistry controllerRegistry = mock(TrafficSignalControllerRegistry.class);
        StrategyDispatchService strategyDispatchService = mock(StrategyDispatchService.class);
        SimulationFrameTimingLogger frameTimingLogger = mock(SimulationFrameTimingLogger.class);
        SimulationService service = new SimulationService(
                cityFlowClient,
                registry,
                webSocketHandler,
                controllerRegistry,
                strategyDispatchService,
                frameTimingLogger
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
        verify(strategyDispatchService, never()).decideAndApply(session, finishedFrame);
        verify(strategyDispatchService).releaseSession("run_finished");
    }
}
