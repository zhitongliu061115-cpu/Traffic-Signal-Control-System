package com.traffic.strategy.dto;

import java.util.Map;

public record ControlDecision(
        String intersectionId,
        String controllerType,
        int phaseIndex,
        String phaseCode,
        Integer durationSec,
        double confidence,
        String reason,
        Map<String, Object> metadata
) {
}
