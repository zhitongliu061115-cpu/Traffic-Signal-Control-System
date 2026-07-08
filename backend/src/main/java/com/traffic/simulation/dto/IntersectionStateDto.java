package com.traffic.simulation.dto;

public record IntersectionStateDto(
        String id,
        int queueCount,
        double avgWait,
        String level
) {
}
