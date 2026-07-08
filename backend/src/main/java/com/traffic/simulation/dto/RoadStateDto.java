package com.traffic.simulation.dto;

public record RoadStateDto(
        String id,
        int vehicleCount,
        int queueCount,
        double avgSpeed,
        String level
) {
}
