package com.traffic.dashboard.dto;

public record DashboardEmergencyVehicleDto(
        String id,
        String type,
        String currentIntersectionId,
        String destination,
        boolean greenWaveActive,
        int eta
) {
}
