package com.traffic.analysis.forecast;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class TrafficForecastDtos {

    private TrafficForecastDtos() {
    }

    public record Observation(
            String intersectionId,
            String observedAt,
            String observationSource,
            double inflowVehiclesPerHour,
            double queueLengthVehicles,
            double averageWaitSeconds,
            double averageSpeedKmh,
            double saturationPercent,
            String phaseName,
            String controlStrategy,
            String deviceStatus
    ) {
    }

    public record PredictRequest(List<Observation> observations) {
    }

    public record ForecastIntersection(
            String id,
            String label,
            double flow,
            double queue,
            @JsonProperty("wait") double waitSeconds,
            String risk,
            String riskLevel
    ) {
    }

    public record ForecastTimelinePoint(
            int horizonMinutes,
            String minute,
            double flow,
            double queue,
            @JsonProperty("wait") double waitSeconds,
            String risk,
            String riskLevel
    ) {
    }

    public record ForecastResponse(
            boolean available,
            String message,
            String modelVersion,
            String modelType,
            String generatedAt,
            String dataUntil,
            String trainedSource,
            List<ForecastIntersection> intersections,
            List<ForecastTimelinePoint> timeline
    ) {
        public static ForecastResponse unavailable(String message) {
            return new ForecastResponse(
                    false,
                    message,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of()
            );
        }
    }
}
