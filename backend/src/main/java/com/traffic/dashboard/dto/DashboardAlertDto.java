package com.traffic.dashboard.dto;

public record DashboardAlertDto(
        String id,
        String type,
        String level,
        String title,
        String location,
        String time,
        String intersectionId,
        boolean acknowledged
) {
}
