package com.traffic.strategy.rl.dto;

import java.util.List;

public record TrafficRIntersectionState(
        String intersectionId,
        Integer currentPhaseIndex,
        String currentPhaseCode,
        List<TrafficRPhaseCandidate> phaseCandidates
) {
}
