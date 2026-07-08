package com.traffic.simulation.dto;

public record CityFlowCreateSimulationRequest(
        String sceneId,
        Double speed
) {
}
