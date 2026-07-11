package com.traffic.dashboard.dto;

public record DashboardRoadDto(
        String id,
        String from,
        String to,
        String name,
        int flow,
        double speed,
        double queueLength,
        double congestionIndex,
        int laneCount,
        String direction,
        String pathJson
) {
}
