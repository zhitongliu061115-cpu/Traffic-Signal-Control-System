package com.traffic.simulation.dto;

public record SignalStateDto(
        String intersectionId,
        int phaseIndex,
        String phaseCode
) {
}
