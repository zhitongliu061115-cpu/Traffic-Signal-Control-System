package com.traffic.emergency.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EVDispatchRequest(
        @NotNull CoordDTO startCoord,
        @NotNull CoordDTO endCoord,
        @NotBlank String evId,
        String evType,
        Integer priority,
        Double maxSpeed
) {
    public EVDispatchRequest {
        if (evType == null) evType = "fire_truck";
        if (priority == null) priority = 1;
        if (maxSpeed == null) maxSpeed = 20.0;
    }
}
