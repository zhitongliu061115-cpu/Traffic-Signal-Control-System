package com.traffic.cityflow.dto;

public record AppliedControlAction(
        String intersectionId,
        int phaseIndex,
        int cityflowPhaseId,
        String phaseCode,
        String status
) {
}
