package com.traffic.simulation.dto;

public record SignalStateDto(
        String intersectionId,
        int phaseIndex,
        String phaseCode,
        Double remainingSec
) {
    public SignalStateDto(String intersectionId, int phaseIndex, String phaseCode) {
        this(intersectionId, phaseIndex, phaseCode, null);
    }
}
