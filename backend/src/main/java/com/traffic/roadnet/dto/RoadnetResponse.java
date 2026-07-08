package com.traffic.roadnet.dto;

import java.util.List;

public record RoadnetResponse(
        String sceneId,
        List<IntersectionDto> intersections,
        List<RoadDto> roads,
        List<RoadLinkDto> roadLinks,
        List<PhaseDto> phases
) {
}
