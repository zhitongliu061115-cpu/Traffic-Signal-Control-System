package com.traffic.simulation.dto;

public record VehicleStateDto(
        String id,
        String roadId,
        int lane,
        double x,
        double y,
        Double lng,
        Double lat,
        double angle,
        double speed
) {
}
