package com.traffic.dashboard.dto;

public record DashboardVehicleDto(
        String id,
        String roadId,
        double progress,
        double speed,
        String type,
        int laneIndex
) {
}
