package com.traffic.simulation.dto;

public record SimulationMetricsDto(
        int vehicleCount,
        Integer activeVehicleCount,
        Integer scheduledDepartureCount,
        int queueCount,
        double avgSpeed,
        double avgWait,
        int throughput
) {
}
