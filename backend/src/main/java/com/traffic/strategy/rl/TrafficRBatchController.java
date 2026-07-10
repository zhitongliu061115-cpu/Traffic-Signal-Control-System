package com.traffic.strategy.rl;

import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.PhaseCandidate;
import com.traffic.strategy.phase.JinanPhaseMapper;
import com.traffic.strategy.rl.client.CloudTrafficRClient;
import com.traffic.strategy.rl.dto.TrafficRBatchPredictRequest;
import com.traffic.strategy.rl.dto.TrafficRBatchPredictResponse;
import com.traffic.strategy.rl.dto.TrafficRIntersectionState;
import com.traffic.strategy.rl.dto.TrafficRObservation;
import com.traffic.strategy.rl.dto.TrafficRPhaseCandidate;
import com.traffic.strategy.rl.dto.TrafficRPredictResponse;
import com.traffic.strategy.rl.dto.TrafficRRoadObservation;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TrafficRBatchController {

    private static final int DEFAULT_DURATION_SEC = 10;

    private final CloudTrafficRClient trafficRClient;
    private final TrafficRProperties properties;

    public TrafficRBatchController(CloudTrafficRClient trafficRClient, TrafficRProperties properties) {
        this.trafficRClient = trafficRClient;
        this.properties = properties;
    }

    public List<ControlDecision> decideBatch(
            String sceneId,
            double simTime,
            SimFrameData frame,
            List<PhaseCandidate> phaseCandidates
    ) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<PhaseCandidate> supportedPhases = trafficRPhaseCandidates(phaseCandidates);
        if (supportedPhases.isEmpty() || frame.signals() == null || frame.signals().isEmpty()) {
            return List.of();
        }

        TrafficRBatchPredictResponse response = trafficRClient.predictBatch(
                toPredictRequest(sceneId, simTime, frame, supportedPhases)
        );
        if (response == null || response.decisions() == null) {
            throw new IllegalStateException("Traffic-R batch returned empty decisions");
        }

        Set<String> validIntersectionIds = frame.signals().stream()
                .filter(Objects::nonNull)
                .map(SignalStateDto::intersectionId)
                .collect(Collectors.toSet());
        Map<String, PhaseCandidate> phasesByCode = supportedPhases.stream()
                .collect(Collectors.toMap(PhaseCandidate::phaseCode, phase -> phase));

        List<ControlDecision> decisions = response.decisions().stream()
                .filter(Objects::nonNull)
                .filter(decision -> validIntersectionIds.contains(decision.intersectionId()))
                .map(decision -> toControlDecision(decision, phasesByCode))
                .toList();
        if (decisions.size() != validIntersectionIds.size()) {
            throw new IllegalStateException("Traffic-R batch returned incomplete decisions: expected="
                    + validIntersectionIds.size() + ", actual=" + decisions.size());
        }
        return decisions;
    }

    private TrafficRBatchPredictRequest toPredictRequest(
            String sceneId,
            double simTime,
            SimFrameData frame,
            List<PhaseCandidate> phaseCandidates
    ) {
        List<TrafficRPhaseCandidate> trafficRPhases = phaseCandidates.stream()
                .map(phase -> new TrafficRPhaseCandidate(JinanPhaseMapper.businessIndex(phase.phaseCode()), phase.phaseCode()))
                .toList();
        List<TrafficRIntersectionState> intersections = frame.signals().stream()
                .filter(Objects::nonNull)
                .map(signal -> new TrafficRIntersectionState(
                        signal.intersectionId(),
                        JinanPhaseMapper.businessIndex(signal.phaseCode()),
                        signal.phaseCode() == null
                                ? JinanPhaseMapper.businessCodeForCityflowPhase(signal.phaseIndex())
                                : signal.phaseCode(),
                        trafficRPhases
                ))
                .toList();
        return new TrafficRBatchPredictRequest(
                sceneId,
                simTime,
                intersections,
                toObservation(frame)
        );
    }

    private TrafficRObservation toObservation(SimFrameData frame) {
        var roads = frame.roads() == null
                ? List.<TrafficRRoadObservation>of()
                : frame.roads().stream()
                .filter(Objects::nonNull)
                .map(road -> new TrafficRRoadObservation(
                        road.id(),
                        road.queueCount(),
                        road.vehicleCount()
                ))
                .toList();
        Map<String, Object> metrics = new HashMap<>();
        if (frame.metrics() != null) {
            metrics.put("vehicleCount", frame.metrics().vehicleCount());
            metrics.put("queueCount", frame.metrics().queueCount());
            metrics.put("avgSpeed", frame.metrics().avgSpeed());
            metrics.put("avgWait", frame.metrics().avgWait());
            metrics.put("throughput", frame.metrics().throughput());
        }
        return new TrafficRObservation(roads, frame.laneStates(), metrics);
    }

    private ControlDecision toControlDecision(TrafficRPredictResponse response, Map<String, PhaseCandidate> phasesByCode) {
        if (!Boolean.TRUE.equals(response.parsedFromModel())) {
            throw new IllegalStateException("Traffic-R batch decision was not parsed from model output: intersectionId="
                    + response.intersectionId());
        }
        if (response.rawOutput() == null || response.rawOutput().isBlank()) {
            throw new IllegalStateException("Traffic-R batch decision rawOutput is empty: intersectionId="
                    + response.intersectionId());
        }
        String modelPhaseCode = response.phaseCode();
        if (!JinanPhaseMapper.isBusinessPhaseCode(modelPhaseCode)) {
            modelPhaseCode = JinanPhaseMapper.businessCode(response.phaseIndex());
        }
        PhaseCandidate selected = phasesByCode.get(modelPhaseCode);
        if (selected == null) {
            throw new IllegalStateException("Traffic-R batch returned unsupported phaseCode: " + response.phaseCode());
        }
        if (response.phaseCode() != null && !response.phaseCode().equals(selected.phaseCode())) {
            throw new IllegalStateException("Traffic-R batch returned inconsistent phase: index="
                    + response.phaseIndex() + ", code=" + response.phaseCode()
                    + ", expectedCode=" + selected.phaseCode());
        }
        int businessPhaseIndex = JinanPhaseMapper.businessIndex(selected.phaseCode());
        int cityflowPhaseIndex = JinanPhaseMapper.cityflowPhaseIndex(selected.phaseCode());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "traffic-r");
        metadata.put("modelStatus", "ok");
        metadata.put("batch", true);
        metadata.put("baseUrl", properties.getBaseUrl());
        metadata.put("decisionIntervalSec", properties.getDecisionIntervalSec());
        metadata.put("timeoutSec", properties.getTimeoutSec());
        metadata.put("modelPhaseIndex", response.phaseIndex());
        metadata.put("modelPhaseCode", response.phaseCode());
        metadata.put("parsedFromModel", response.parsedFromModel());
        metadata.put("inferenceTimeSec", response.inferenceTimeSec());
        metadata.put("rawOutput", response.rawOutput());
        metadata.put("businessPhaseIndex", businessPhaseIndex);
        metadata.put("businessPhaseCode", selected.phaseCode());
        metadata.put("cityflowPhaseIndex", cityflowPhaseIndex);
        metadata.put("cityflowPhaseId", cityflowPhaseIndex - 1);
        return new ControlDecision(
                response.intersectionId(),
                "traffic-r",
                cityflowPhaseIndex,
                selected.phaseCode(),
                DEFAULT_DURATION_SEC,
                response.confidence() == null ? 0.0 : response.confidence(),
                response.reason() == null ? "Traffic-R batch selected phase from cloud model response" : response.reason(),
                metadata
        );
    }

    private List<PhaseCandidate> trafficRPhaseCandidates(List<PhaseCandidate> phaseCandidates) {
        if (phaseCandidates == null) {
            return List.of();
        }
        return phaseCandidates.stream()
                .filter(Objects::nonNull)
                .filter(phase -> phase.phaseCode() != null)
                .filter(phase -> JinanPhaseMapper.isBusinessPhaseCode(phase.phaseCode()))
                .toList();
    }
}
