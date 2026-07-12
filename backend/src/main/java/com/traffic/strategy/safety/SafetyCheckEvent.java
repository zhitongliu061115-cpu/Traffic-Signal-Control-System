package com.traffic.strategy.safety;

public record SafetyCheckEvent(
        String constraintType,
        String action,
        Integer beforePhaseIndex,
        String beforePhaseCode,
        Integer afterPhaseIndex,
        String afterPhaseCode,
        String reason
) {
}
