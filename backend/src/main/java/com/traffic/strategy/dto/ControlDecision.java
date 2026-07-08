package com.traffic.strategy.dto;

public record ControlDecision(
        String intersectionId,
        int phaseIndex,
        String phaseCode,
        String reason
) {
}
