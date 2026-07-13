package com.traffic.strategy.service;

import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.simulation.session.SimulationSessionState;
import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.AppliedControlResult;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import com.traffic.strategy.rl.TrafficRAsyncDecisionService;
import com.traffic.strategy.rl.TrafficRDecisionAuditLogger;
import com.traffic.strategy.safety.SafetyLayerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StrategyDispatchServiceSafetyTest {

    @Test
    void illegalStrategyDecisionIsNotSubmittedToCityFlow() {
        CityFlowClient cityFlowClient = mock(CityFlowClient.class);
        TrafficSignalControllerRegistry registry = new TrafficSignalControllerRegistry(List.of(new IllegalPhaseController()));
        TrafficRAsyncDecisionService trafficRAsyncDecisionService = mock(TrafficRAsyncDecisionService.class);
        TrafficRDecisionAuditLogger auditLogger = mock(TrafficRDecisionAuditLogger.class);
        StrategyDispatchService service = new StrategyDispatchService(
                cityFlowClient,
                registry,
                trafficRAsyncDecisionService,
                auditLogger,
                new SafetyLayerService()
        );
        SimulationRuntimeSession session = new SimulationRuntimeSession(
                "sid-safety",
                "jinan_3x4",
                "unsafe-test",
                SimulationSessionState.RUNNING
        );
        SimFrameData frame = new SimFrameData(
                30.0,
                "running",
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(new SignalStateDto("intersection_1_1", 2, "ETWT", null, null, null)),
                null,
                List.of(),
                List.of()
        );

        AppliedControlResult result = service.decideAndApply(session, frame);

        verify(cityFlowClient, never()).applyControlActions(anyString(), any());
        assertEquals(1, result.decisions().size());
        ControlDecision auditDecision = result.decisions().get(0);
        assertEquals(2, auditDecision.phaseIndex());
        assertEquals("ETWT", auditDecision.phaseCode());
        assertEquals(Boolean.TRUE, auditDecision.metadata().get("safetyChecked"));
        assertEquals(Boolean.TRUE, auditDecision.metadata().get("safetyRejected"));
        assertTrue(auditDecision.reason().contains("Safety layer fallback"));
    }

    private static class IllegalPhaseController implements TrafficSignalController {

        @Override
        public String controllerType() {
            return "unsafe-test";
        }

        @Override
        public ControlDecision decide(ControlRequest request) {
            return new ControlDecision(
                    request.intersectionId(),
                    controllerType(),
                    99,
                    "BAD",
                    10,
                    0.9,
                    "intentionally illegal phase",
                    Map.of("source", "unit-test")
            );
        }
    }
}
