package com.traffic.dashboard.dto;

public record DashboardIntersectionDto(
        String id,
        String name,
        double x,
        double y,
        double lng,
        double lat,
        int row,
        int col,
        String currentPhase,
        int greenRemain,
        int queueLength,
        double averageDelay,
        double congestionIndex,
        String deviceStatus
) {
}
