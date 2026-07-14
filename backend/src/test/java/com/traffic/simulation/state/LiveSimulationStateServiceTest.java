package com.traffic.simulation.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.runtime.query.RuntimeQueryDtos.CurrentSimulationState;
import com.traffic.simulation.dto.IntersectionStateDto;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SimulationMetricsDto;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.simulation.session.SimulationSessionState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LiveSimulationStateServiceTest {

    @Test
    void currentStateWithoutSidPrefersSessionWithCachedFrames() {
        LiveSimulationStateService service = new LiveSimulationStateService(new ObjectMapper());
        service.registerSession("sid-with-frame", "jinan_3x4", "rl", "running", null);
        service.recordFrame(
                new SimulationRuntimeSession("sid-with-frame", "jinan_3x4", "rl", SimulationSessionState.RUNNING),
                7,
                frameWithCongestedIntersection(),
                List.of()
        );

        service.registerSession("sid-without-frame", "jinan_3x4", "rl", "created", null);
        service.updateSessionStatus("sid-without-frame", "running");

        CurrentSimulationState state = service.getCurrentSimulationState(null);

        assertEquals("sid-with-frame", state.session().sid());
        assertNotNull(state.latestFrame());
        assertEquals(7, state.latestFrame().seq());
        assertEquals(1, state.signals().size());
        assertEquals("intersection_1_1", state.signals().get(0).cityflowIntersectionId());
    }

    private SimFrameData frameWithCongestedIntersection() {
        return new SimFrameData(
                120.0,
                "running",
                List.of(),
                List.of(),
                Map.of(),
                List.of(new IntersectionStateDto("intersection_1_1", 21, 88.5, "HEAVY")),
                List.of(new SignalStateDto("intersection_1_1", 2, "ETWT")),
                new SimulationMetricsDto(30, 30, 0, 21, 2.2, 88.5, 5),
                List.of(),
                List.of()
        );
    }
}
