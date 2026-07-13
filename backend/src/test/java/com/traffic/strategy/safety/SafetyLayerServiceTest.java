package com.traffic.strategy.safety;

import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.simulation.session.SimulationSessionState;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.PhaseCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyLayerServiceTest {

    @Test
    void rejectsIllegalPhaseBeforeCityFlowApply() {
        SafetyLayerService service = new SafetyLayerService();
        SimulationRuntimeSession session = new SimulationRuntimeSession(
                "sid-1",
                "jinan_3x4",
                "traffic-r",
                SimulationSessionState.RUNNING
        );
        SimFrameData frame = new SimFrameData(
                30.0,
                "running",
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(new SignalStateDto("intersection_1_1", 2, "ETWT")),
                null,
                List.of(),
                List.of()
        );
        ControlDecision illegal = new ControlDecision(
                "intersection_1_1",
                "traffic-r",
                99,
                "BAD",
                10,
                0.9,
                "model suggested unsupported phase",
                Map.of("source", "traffic-r")
        );

        SafetyReviewResult result = service.review(session, frame, List.of(illegal), List.of(
                new PhaseCandidate(2, "ETWT", List.of()),
                new PhaseCandidate(3, "NTST", List.of())
        ));

        assertTrue(result.safeDecisions().isEmpty());
        assertEquals(1, result.auditDecisions().size());
        ControlDecision audit = result.auditDecisions().get(0);
        assertEquals(2, audit.phaseIndex());
        assertEquals("ETWT", audit.phaseCode());
        assertEquals(Boolean.TRUE, audit.metadata().get("safetyChecked"));
        assertEquals(Boolean.TRUE, audit.metadata().get("safetyRejected"));
        assertFalse(((List<?>) audit.metadata().get("safetyEvents")).isEmpty());
    }

    @Test
    void allowsLegalPhaseAfterSafetyCheck() {
        SafetyLayerService service = new SafetyLayerService();
        SimulationRuntimeSession session = new SimulationRuntimeSession(
                "sid-2",
                "jinan_3x4",
                "fixed-time",
                SimulationSessionState.RUNNING
        );
        SimFrameData frame = new SimFrameData(
                30.0,
                "running",
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(new SignalStateDto("intersection_1_1", 2, "ETWT")),
                null,
                List.of(),
                List.of()
        );
        ControlDecision legal = new ControlDecision(
                "intersection_1_1",
                "fixed-time",
                3,
                "NTST",
                10,
                1.0,
                "cycle switch",
                Map.of()
        );

        SafetyReviewResult result = service.review(session, frame, List.of(legal), List.of(
                new PhaseCandidate(2, "ETWT", List.of()),
                new PhaseCandidate(3, "NTST", List.of())
        ));

        assertEquals(1, result.safeDecisions().size());
        assertTrue(result.auditDecisions().isEmpty());
        assertEquals(Boolean.TRUE, result.safeDecisions().get(0).metadata().get("safetyChecked"));
        assertEquals(Boolean.TRUE, result.safeDecisions().get(0).metadata().get("safetyAllowed"));
    }

    @Test
    void tracksPhaseHoldTimeEvenWhenNoStrategyDecisionIsGenerated() {
        SafetyLayerService service = new SafetyLayerService();
        SimulationRuntimeSession session = new SimulationRuntimeSession(
                "sid-3",
                "jinan_3x4",
                "traffic-r",
                SimulationSessionState.RUNNING
        );

        service.review(session, frameAt(0.0, 2, "ETWT"), List.of(), phaseCandidates());
        service.review(session, frameAt(10.0, 3, "NTST"), List.of(), phaseCandidates());

        ControlDecision trafficRDecision = new ControlDecision(
                "intersection_1_1",
                "traffic-r",
                4,
                "ELWL",
                10,
                0.85,
                "Traffic-R selected phase from cloud model response",
                Map.of("source", "traffic-r")
        );

        SafetyReviewResult result = service.review(
                session,
                frameAt(16.0, 3, "NTST"),
                List.of(trafficRDecision),
                phaseCandidates()
        );

        assertEquals(1, result.safeDecisions().size());
        assertTrue(result.auditDecisions().isEmpty());
        assertEquals(Boolean.TRUE, result.safeDecisions().get(0).metadata().get("safetyAllowed"));
    }

    private SimFrameData frameAt(double simTime, int phaseIndex, String phaseCode) {
        return new SimFrameData(
                simTime,
                "running",
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(new SignalStateDto("intersection_1_1", phaseIndex, phaseCode)),
                null,
                List.of(),
                List.of()
        );
    }

    private List<PhaseCandidate> phaseCandidates() {
        return List.of(
                new PhaseCandidate(2, "ETWT", List.of()),
                new PhaseCandidate(3, "NTST", List.of()),
                new PhaseCandidate(4, "ELWL", List.of())
        );
    }
}
