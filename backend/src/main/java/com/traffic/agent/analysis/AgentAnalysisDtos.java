package com.traffic.agent.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentAnalysisDtos {

    private AgentAnalysisDtos() {
    }

    public record DiagnosisReport(
            String conclusion,
            List<String> evidence,
            List<String> impactScope,
            List<String> possibleCauses,
            List<String> recommendations,
            double confidence,
            List<String> humanConfirmationRequired,
            Map<String, Object> data,
            Instant generatedAt
    ) {
    }

    public record RegionMetricsReport(
            String regionId,
            String sid,
            int intersectionCount,
            int sampleCount,
            double avgQueue,
            double maxQueue,
            double avgWait,
            double maxWait,
            double avgSpeed,
            int congestedIntersectionCount,
            List<String> evidence,
            List<String> warnings,
            Instant generatedAt
    ) {
    }

    public record StrategyMetricItem(
            String sid,
            String controllerType,
            long frameCount,
            double avgVehicleCount,
            double avgQueueCount,
            double maxQueueCount,
            double avgSpeed,
            double avgWait,
            double throughput,
            String assessment
    ) {
    }

    public record StrategyCompareReport(
            List<StrategyMetricItem> strategies,
            List<String> evidence,
            List<String> recommendations,
            List<String> warnings,
            Instant generatedAt
    ) {
    }
}
