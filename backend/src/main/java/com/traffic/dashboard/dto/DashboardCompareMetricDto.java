package com.traffic.dashboard.dto;

public record DashboardCompareMetricDto(
        String name,
        double traditional,
        double ai,
        String unit,
        String direction
) {
}
