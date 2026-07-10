package com.traffic.strategy.rl.dto;

import java.util.List;

public record TrafficRPredictRequest(
        String sceneId,
        String intersectionId,
        double simTime,
        Integer currentPhaseIndex,
        String currentPhaseCode,
        List<TrafficRPhaseCandidate> phaseCandidates,
        TrafficRObservation observation
) {
}
