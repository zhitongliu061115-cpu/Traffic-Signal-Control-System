package com.traffic.simulation.dto;

public record VehicleStateDto(
        String id,
        String roadId,
        int lane,
        double x,
        double y,
        double angle,
        double speed
) {
}
