package com.traffic.simulation.dto;

import java.util.List;

public record EvStatusDto(
        String evId,
        String cfVehicleId,
        String evType,
        int priority,
        List<String> route,
        int passedCount,
        int totalCount,
        boolean completed,
        double elapsedTime
) {
}
