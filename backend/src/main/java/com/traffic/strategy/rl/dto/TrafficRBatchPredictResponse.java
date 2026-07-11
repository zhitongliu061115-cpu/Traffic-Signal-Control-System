package com.traffic.strategy.rl.dto;

import java.util.List;

public record TrafficRBatchPredictResponse(
        String sceneId,
        double simTime,
        List<TrafficRPredictResponse> decisions
) {
}
