package com.traffic.strategy.rl.dto;

public record TrafficRRoadObservation(
        String id,
        int queueCount,
        int vehicleCount
) {
}
