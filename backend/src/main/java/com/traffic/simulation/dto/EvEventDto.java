package com.traffic.simulation.dto;

public record EvEventDto(
        String evId,
        String evType,
        int priority,
        String intersectionId,
        String decision,
        int phaseIndex,
        int phaseIndexBefore,
        double timestamp,
        String status,
        String blockedBy
) {
}
