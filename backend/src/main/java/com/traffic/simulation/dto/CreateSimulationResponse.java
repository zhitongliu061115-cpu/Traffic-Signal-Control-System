package com.traffic.simulation.dto;

public record CreateSimulationResponse(
        String sid,
        String sceneId,
        String status
) {
}
