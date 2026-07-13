package com.traffic.simulation.dto;

public record SignalStateDto(
        String intersectionId,
        int phaseIndex,
        String phaseCode,
        Double remainingSec,
        Double phaseStartedAt,
        Double appliedDurationSec
) {
    public SignalStateDto(String intersectionId, int phaseIndex, String phaseCode) {
        this(intersectionId, phaseIndex, phaseCode, null, null, null);
    }
}
