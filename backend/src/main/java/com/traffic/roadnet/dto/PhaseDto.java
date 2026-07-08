package com.traffic.roadnet.dto;

import java.util.List;

public record PhaseDto(
        String intersectionId,
        int phaseIndex,
        String phaseCode,
        List<Integer> roadLinkIndexes
) {
}
