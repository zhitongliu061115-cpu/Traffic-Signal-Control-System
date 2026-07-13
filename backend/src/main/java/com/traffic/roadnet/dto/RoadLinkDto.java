package com.traffic.roadnet.dto;

import java.util.List;

public record RoadLinkDto(
        String intersectionId,
        int index,
        String fromRoadId,
        String toRoadId,
        String type,
        List<LaneLinkDto> laneLinks
) {
}
