package com.traffic.dashboard.dto;

import java.util.List;
import java.util.Map;

public record DashboardBootstrapResponse(
        List<DashboardIntersectionDto> intersections,
        List<DashboardRoadDto> roads,
        List<DashboardVehicleDto> vehicles,
        DashboardEmergencyVehicleDto emergencyVehicle,
        List<String> emergencyRoute,
        List<DashboardAlertDto> alerts,
        DashboardStatisticsDto statistics,
        Map<String, DashboardCompareMetricDto> compareMetrics,
        List<DashboardTrendPointDto> congestionTrend,
        Map<String, String> assistantReplies
) {
}
