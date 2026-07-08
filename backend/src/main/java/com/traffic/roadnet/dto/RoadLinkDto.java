package com.traffic.roadnet.dto;

public record RoadLinkDto(
        String intersectionId,
        int index,
        String fromRoadId,
        String toRoadId,
        String type
) {
}
