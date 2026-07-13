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
}
