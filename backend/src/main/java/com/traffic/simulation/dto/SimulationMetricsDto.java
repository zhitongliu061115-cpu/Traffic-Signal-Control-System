package com.traffic.simulation.dto;

public record SimulationMetricsDto(
        int vehicleCount,
        int queueCount,
        double avgSpeed,
        double avgWait,
        int throughput
) {
}
