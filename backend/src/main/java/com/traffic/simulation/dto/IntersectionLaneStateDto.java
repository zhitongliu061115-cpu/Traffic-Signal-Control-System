package com.traffic.simulation.dto;

import java.util.Map;

public record IntersectionLaneStateDto(
        Map<String, LaneMovementStateDto> lanes
) {
}
