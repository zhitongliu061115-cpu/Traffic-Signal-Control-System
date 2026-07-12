package com.traffic.runtime.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RuntimeQueryDtos {

    private RuntimeQueryDtos() {
    }

    public record SessionSummary(
            String id,
            String sid,
            String sceneCode,
            String controllerType,
            Double speed,
            Double warmupSeconds,
            String status,
            Instant createdAt,
            Instant startedAt,
            Instant endedAt,
            Instant updatedAt
    ) {
    }

    public record FrameSummary(
            String id,
            long seq,
            double simTime,
            int vehicleCount,
            int queueCount,
            double avgSpeed,
            double avgWait,
            int throughput,
            String status,
            int signalCount,
            Instant capturedAt
    ) {
    }

    public record SignalSnapshot(
            String intersectionId,
            String cityflowIntersectionId,
            Integer phaseIndex,
            String phaseCode,
            int queueCount,
            double avgWait,
            String level
    ) {
    }

    public record CurrentSimulationState(
            SessionSummary session,
            FrameSummary latestFrame,
            long persistedFrameCount,
            List<SignalSnapshot> signals
    ) {
    }

    public record PhaseInfo(
            String id,
            int phaseIndex,
            String phaseCode,
            String phaseName,
            String phaseType,
            int defaultGreenSec,
            int yellowSec,
            int allRedSec
    ) {
    }

    public record MovementSnapshot(
            String movementCode,
            int queueLen,
            int vehicleCount,
            double avgWaitTime,
            Double avgSpeed,
            List<Integer> cells,
            double simTime,
            long frameSeq
    ) {
    }

    public record RoadLinkInfo(
            String id,
            int cityflowIndex,
            String fromRoadId,
            String toRoadId,
            String movementType
    ) {
    }

    public record IntersectionDetail(
            String id,
            String sceneCode,
            String cityflowId,
            String mapIntersectionId,
            String name,
            String type,
            boolean virtual,
            Double longitude,
            Double latitude,
            double x,
            double y,
            SignalSnapshot latestState,
            List<MovementSnapshot> movements,
            List<PhaseInfo> phases,
            List<RoadLinkInfo> roadLinks
    ) {
    }

    public record LaneInfo(
            String id,
            int cityflowLaneIndex,
            String laneCode,
            String direction,
            String movement,
            Double width,
            Double speedLimit
    ) {
    }

    public record RoadSnapshot(
            int vehicleCount,
            int queueCount,
            double avgSpeed,
            String level,
            double simTime,
            long frameSeq
    ) {
    }

    public record RoadDetail(
            String id,
            String sceneCode,
            String cityflowId,
            String fromIntersectionId,
            String toIntersectionId,
            String name,
            String direction,
            double lengthM,
            Double speedLimit,
            int laneCount,
            String geometry,
            RoadSnapshot latestState,
            List<LaneInfo> lanes
    ) {
    }

    public record ControlDecisionSummary(
            String id,
            String sid,
            String intersectionId,
            String cityflowIntersectionId,
            double simTime,
            String controllerType,
            String requestedPhaseId,
            String requestedPhaseCode,
            String finalPhaseId,
            String finalPhaseCode,
            int durationSec,
            String status,
            String reason,
            double confidence,
            String metadata,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record DecisionTraceEntry(
            String id,
            String stage,
            String inputPayload,
            String outputPayload,
            String message,
            Instant createdAt
    ) {
    }

    public record MaxPressureScoreSummary(
            String id,
            String phaseId,
            int phaseIndex,
            String phaseCode,
            double pressureScore,
            String detailPayload,
            Instant createdAt
    ) {
    }

    public record DecisionEffectSummary(
            String id,
            String beforeFrameId,
            String afterFrameId,
            int horizonSec,
            int queueBefore,
            int queueAfter,
            int queueDelta,
            double avgWaitBefore,
            double avgWaitAfter,
            double avgWaitDelta,
            double avgSpeedBefore,
            double avgSpeedAfter,
            double avgSpeedDelta,
            int throughputBefore,
            int throughputAfter,
            int throughputDelta,
            String evaluationLabel,
            String detailPayload,
            Instant createdAt
    ) {
    }

    public record DecisionTraceResponse(
            ControlDecisionSummary decision,
            List<DecisionTraceEntry> traces,
            List<MaxPressureScoreSummary> maxPressureScores,
            DecisionEffectSummary effect
    ) {
    }

    public record InferenceResultSummary(
            String id,
            String intersectionId,
            String cityflowIntersectionId,
            String phaseId,
            String phaseCode,
            Double confidence,
            boolean valid,
            String reason,
            String rawOutput,
            Instant createdAt
    ) {
    }

    public record ModelInferenceLogSummary(
            String id,
            String sid,
            double simTime,
            String requestId,
            String modelName,
            String requestPayload,
            String promptText,
            String rawOutput,
            String responsePayload,
            String parsedPhaseCode,
            boolean valid,
            int latencyMs,
            String status,
            String errorMessage,
            Instant createdAt,
            List<InferenceResultSummary> results
    ) {
    }

    public record ServiceHealthItem(
            String id,
            String serviceName,
            String status,
            int latencyMs,
            String detailPayload,
            Instant checkedAt
    ) {
    }

    public record SystemHealthResponse(
            boolean databaseConnected,
            Map<String, Long> tableCounts,
            Map<String, Long> sessionStatusCounts,
            List<ServiceHealthItem> services
    ) {
    }

    public record FallbackEventSummary(
            String id,
            String sid,
            String intersectionId,
            String cityflowIntersectionId,
            String fromStrategy,
            String toStrategy,
            String reason,
            double simTime,
            Instant createdAt
    ) {
    }

    public record SafetyEventSummary(
            String id,
            String decisionId,
            String sid,
            String intersectionId,
            String cityflowIntersectionId,
            String constraintType,
            String action,
            String beforePhaseId,
            String beforePhaseCode,
            String afterPhaseId,
            String afterPhaseCode,
            String reason,
            Instant createdAt
    ) {
    }

    public record AlertEventSummary(
            String id,
            String sid,
            String alertType,
            String level,
            String targetType,
            String targetId,
            String title,
            String description,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record EmergencyEventSummary(
            String id,
            String sid,
            String eventCode,
            String vehicleId,
            String vehicleType,
            int priority,
            String status,
            String startCoord,
            String endCoord,
            Instant createdAt,
            Instant updatedAt,
            Instant endedAt,
            String errorMessage
    ) {
    }
}
