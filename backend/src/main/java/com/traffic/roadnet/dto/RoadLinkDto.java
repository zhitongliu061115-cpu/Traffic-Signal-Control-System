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
    public RoadLinkDto(String intersectionId, int index, String fromRoadId, String toRoadId, String type) {
        this(intersectionId, index, fromRoadId, toRoadId, type, List.of());
    }
}
