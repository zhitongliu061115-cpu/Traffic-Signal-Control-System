package com.traffic.roadnet.dto;

import java.util.List;

public record RoadDto(
        String id,
        String from,
        String to,
        List<PointDto> points,
        int laneCount,
        List<LaneDto> lanes
) {
    public RoadDto(String id, String from, String to, List<PointDto> points, int laneCount) {
        this(id, from, to, points, laneCount, List.of());
    }
}
