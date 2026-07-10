package com.traffic.strategy.rl;

import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import com.traffic.strategy.dto.PhaseCandidate;
import com.traffic.strategy.phase.JinanPhaseMapper;
import com.traffic.strategy.rl.client.CloudTrafficRClient;
import com.traffic.strategy.rl.dto.TrafficRObservation;
import com.traffic.strategy.rl.dto.TrafficRPhaseCandidate;
import com.traffic.strategy.rl.dto.TrafficRPredictRequest;
import com.traffic.strategy.rl.dto.TrafficRPredictResponse;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class RlController implements TrafficSignalController {

    private static final int DEFAULT_DURATION_SEC = 10;

    private final CloudTrafficRClient trafficRClient;
    private final TrafficRProperties properties;

    public RlController(CloudTrafficRClient trafficRClient, TrafficRProperties properties) {
        this.trafficRClient = trafficRClient;
        this.properties = properties;
    }

    @Override
    public String controllerType() {
        return "traffic-r";
    }

    @Override
    public ControlDecision decide(ControlRequest request) {
        if (!properties.isEnabled()) {
            return keepCurrentPhase(request, "Traffic-R is disabled by configuration", "disabled");
        }

        List<PhaseCandidate> phaseCandidates = trafficRPhaseCandidates(request.phaseCandidates());
        if (phaseCandidates.isEmpty()) {
            return keepCurrentPhase(request, "Traffic-R cannot decide because no supported Jinan phase candidates were provided", "no_supported_phase_candidates");
        }

        TrafficRPredictResponse response = trafficRClient.predict(toPredictRequest(request, phaseCandidates));
        PhaseCandidate selected = validateAndFindSelectedPhase(response, phaseCandidates);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "traffic-r");
        metadata.put("modelStatus", "ok");
        metadata.put("baseUrl", properties.getBaseUrl());
        metadata.put("decisionIntervalSec", properties.getDecisionIntervalSec());
        metadata.put("timeoutSec", properties.getTimeoutSec());
        metadata.put("modelPhaseIndex", response.phaseIndex());
        metadata.put("modelPhaseCode", response.phaseCode());
        metadata.put("businessPhaseIndex", JinanPhaseMapper.businessIndex(selected.phaseCode()));
        metadata.put("businessPhaseCode", selected.phaseCode());
        metadata.put("cityflowPhaseIndex", JinanPhaseMapper.cityflowPhaseIndex(selected.phaseCode()));
        metadata.put("cityflowPhaseId", JinanPhaseMapper.cityflowPhaseIndex(selected.phaseCode()) - 1);
        return new ControlDecision(
                request.intersectionId(),
                controllerType(),
                JinanPhaseMapper.cityflowPhaseIndex(selected.phaseCode()),
                selected.phaseCode(),
                DEFAULT_DURATION_SEC,
                response.confidence() == null ? 0.0 : response.confidence(),
                response.reason() == null ? "Traffic-R selected phase from cloud model response" : response.reason(),
                metadata
        );
    }

    private TrafficRPredictRequest toPredictRequest(ControlRequest request, List<PhaseCandidate> phaseCandidates) {
        var roads = request.frame() == null || request.frame().roads() == null
                ? List.<com.traffic.strategy.rl.dto.TrafficRRoadObservation>of()
                : request.frame().roads().stream()
                .filter(Objects::nonNull)
                .map(road -> new com.traffic.strategy.rl.dto.TrafficRRoadObservation(
                        road.id(),
                        road.queueCount(),
                        road.vehicleCount()
                ))
                .toList();
        Map<String, Object> metrics = new HashMap<>();
        if (request.frame() != null && request.frame().metrics() != null) {
            var frameMetrics = request.frame().metrics();
            metrics.put("vehicleCount", frameMetrics.vehicleCount());
            metrics.put("queueCount", frameMetrics.queueCount());
            metrics.put("avgSpeed", frameMetrics.avgSpeed());
            metrics.put("avgWait", frameMetrics.avgWait());
            metrics.put("throughput", frameMetrics.throughput());
        }
        return new TrafficRPredictRequest(
                request.sceneId(),
                request.intersectionId(),
                request.simTime(),
                request.currentPhaseIndex(),
                request.currentPhaseCode(),
                phaseCandidates.stream()
                        .map(phase -> new TrafficRPhaseCandidate(JinanPhaseMapper.businessIndex(phase.phaseCode()), phase.phaseCode()))
                        .toList(),
                new TrafficRObservation(
                        roads,
                        request.frame() == null ? null : request.frame().laneStates(),
                        metrics
                )
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

    private PhaseCandidate validateAndFindSelectedPhase(TrafficRPredictResponse response, List<PhaseCandidate> phaseCandidates) {
        if (response == null) {
            throw new IllegalStateException("Traffic-R returned empty response");
        }
        if (!Boolean.TRUE.equals(response.parsedFromModel())) {
            throw new IllegalStateException("Traffic-R returned a decision that was not parsed from model output");
        }
        if (response.rawOutput() == null || response.rawOutput().isBlank()) {
            throw new IllegalStateException("Traffic-R returned empty raw model output");
        }
        String phaseCode = response.phaseCode();
        if (!JinanPhaseMapper.isBusinessPhaseCode(phaseCode)) {
            phaseCode = JinanPhaseMapper.businessCode(response.phaseIndex());
        }
        final String selectedCode = phaseCode;
        return phaseCandidates.stream()
                .filter(phase -> selectedCode.equals(phase.phaseCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Traffic-R returned unsupported phaseCode: " + response.phaseCode()));
    }

    private ControlDecision keepCurrentPhase(ControlRequest request, String reason, String status) {
        Integer currentPhase = request.currentPhaseIndex();
        int phaseIndex = currentPhase == null ? 1 : currentPhase;
        String phaseCode = request.currentPhaseCode() == null ? "ETWT" : request.currentPhaseCode();
        return new ControlDecision(
                request.intersectionId(),
                controllerType(),
                phaseIndex,
                phaseCode,
                DEFAULT_DURATION_SEC,
                0.0,
                reason,
                Map.of("status", status, "source", "traffic-r")
        );
    }
}
