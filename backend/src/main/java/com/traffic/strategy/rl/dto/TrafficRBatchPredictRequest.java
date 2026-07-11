package com.traffic.strategy.rl.dto;

import java.util.List;

public record TrafficRBatchPredictRequest(
        String sceneId,
        double simTime,
        List<TrafficRIntersectionState> intersections,
        TrafficRObservation observation
) {
}
