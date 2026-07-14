package com.traffic.emergency.service;

import com.traffic.agent.service.AgentEmergencyDispatchMemory;
import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.emergency.dto.EVDispatchRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmergencyServiceTest {

    @Test
    void dispatchRemembersIntersectionEndpointsForAgentFollowUp() {
        CityFlowClient cityFlowClient = mock(CityFlowClient.class);
        AgentEmergencyDispatchMemory memory = new AgentEmergencyDispatchMemory();
        EmergencyService service = new EmergencyService(cityFlowClient, memory);
        when(cityFlowClient.dispatchEV(eq("run_001"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Map.of(
                        "sid", "run_001",
                        "evId", "EV-1",
                        "evType", "ambulance",
                        "priority", 3,
                        "cfVehicleId", "veh-1",
                        "route", List.of("intersection_1_1", "intersection_1_2"),
                        "routeRoads", List.of("road_1"),
                        "estimatedTravelTime", 42.0
                ));

        service.dispatch("run_001", new EVDispatchRequest(
                null,
                null,
                "EV-1",
                "ambulance",
                3,
                20.0,
                "intersection_1_1",
                "intersection_1_2"
        ));

        var remembered = memory.latest("run_001");
        assertTrue(remembered.isPresent());
        assertEquals("intersection_1_1", remembered.get().startIntersection());
        assertEquals("intersection_1_2", remembered.get().endIntersection());
        assertEquals("EV-1", remembered.get().evId());
        assertEquals("ambulance", remembered.get().evType());
        assertEquals(3, remembered.get().priority());
    }
}
