package com.traffic.dashboard.dto;

public record DashboardStatisticsDto(
        int totalFlow,
        double averageSpeed,
        double averageWaitTime,
        double congestionIndex,
        int congestedRoadCount,
        int optimizedIntersectionCount,
        int emergencyVehicleCount,
        double deviceOnlineRate,
        int todayAlertCount,
        int greenWaveCount
) {
}
