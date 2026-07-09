package com.traffic.strategy.dto;

import java.util.List;

public record PhaseCandidate(
        int phaseIndex,
        String phaseCode,
        List<Integer> roadLinkIndexes
) {
}
