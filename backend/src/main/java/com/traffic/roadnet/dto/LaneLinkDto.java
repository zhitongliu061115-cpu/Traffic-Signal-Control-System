package com.traffic.roadnet.dto;

import java.util.List;

public record LaneLinkDto(
        String id,
        int startLaneIndex,
        int endLaneIndex,
        List<PointDto> points
) {
}
