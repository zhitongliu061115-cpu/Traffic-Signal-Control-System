package com.traffic.simulation.dto;

public record VehicleStateDto(
        String id,
        String roadId,
        int lane,
        double x,
        double y,
        double angle,
        double speed,
        String drivableId,
        String drivableType,
        double distance,
        String nextRoadId,
        Integer nextLane
) {
    public VehicleStateDto(String id, String roadId, int lane, double x, double y, double angle, double speed) {
        this(id, roadId, lane, x, y, angle, speed, null, null, 0.0, null, null);
    }
}
