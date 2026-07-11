package com.traffic.emergency.dto;

import java.util.List;

public record EVDispatchResponse(
        String cfVehicleId,
        String sid,
        String evId,
        String evType,
        int priority,
        List<String> route,
        List<String> routeRoads,
        double estimatedTravelTime
) {
}
