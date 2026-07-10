package com.traffic.strategy.rl.dto;

public record TrafficRPredictResponse(
        String intersectionId,
        int phaseIndex,
        String phaseCode,
        Integer durationSec,
        Double confidence,
        String reason,
        Boolean parsedFromModel,
        String rawOutput,
        Double inferenceTimeSec
) {
}
