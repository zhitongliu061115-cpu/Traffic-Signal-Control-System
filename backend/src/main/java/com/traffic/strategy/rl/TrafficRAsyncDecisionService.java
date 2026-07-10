package com.traffic.strategy.rl;

import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.strategy.dto.ControlRequest;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.PhaseCandidate;
import com.traffic.strategy.maxpressure.MaxPressureController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TrafficRAsyncDecisionService {

    private static final Logger log = LoggerFactory.getLogger(TrafficRAsyncDecisionService.class);
    private static final int FAILURE_THRESHOLD = 3;
    private static final int RECOVERY_THRESHOLD = 3;

    private final TrafficRProperties properties;
    private final TrafficRBatchController trafficRBatchController;
    private final MaxPressureController maxPressureController;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, DecisionSlot> slots = new ConcurrentHashMap<>();

    public TrafficRAsyncDecisionService(
            TrafficRProperties properties,
            TrafficRBatchController trafficRBatchController,
            MaxPressureController maxPressureController
    ) {
        this.properties = properties;
        this.trafficRBatchController = trafficRBatchController;
        this.maxPressureController = maxPressureController;
    }

    public Optional<List<ControlDecision>> pollDecision(
            String sid,
            String sceneId,
            double simTime,
            SimFrameData frame,
            List<PhaseCandidate> phaseCandidates
    ) {
        DecisionSlot slot = slots.computeIfAbsent(sid, ignored -> new DecisionSlot());
        synchronized (slot) {
            List<ControlDecision> readyDecisions = slot.readyDecisions;
            slot.readyDecisions = null;
            if (shouldSubmit(slot, simTime)) {
                submit(slot, sid, sceneId, simTime, frame, phaseCandidates);
            }
            if (readyDecisions != null && !readyDecisions.isEmpty()) {
                return Optional.of(readyDecisions);
            }
            if (slot.fallbackActive && shouldApplyFallback(slot, simTime)) {
                slot.lastFallbackSimTime = simTime;
                return Optional.of(maxPressureDecisions(slot, sid, sceneId, simTime, frame, phaseCandidates));
            }
            return Optional.empty();
        }
    }

    private boolean shouldSubmit(DecisionSlot slot, double simTime) {
        if (slot.inFlight) {
            return false;
        }
        if (slot.lastRequestSimTime == null) {
            return true;
        }
        return simTime - slot.lastRequestSimTime >= properties.getDecisionIntervalSec();
    }

    private boolean shouldApplyFallback(DecisionSlot slot, double simTime) {
        if (slot.lastFallbackSimTime == null) {
            return true;
        }
        return simTime - slot.lastFallbackSimTime >= properties.getDecisionIntervalSec();
    }

    private void submit(
            DecisionSlot slot,
            String sid,
            String sceneId,
            double simTime,
            SimFrameData frame,
            List<PhaseCandidate> phaseCandidates
    ) {
        slot.inFlight = true;
        slot.lastRequestSimTime = simTime;
        CompletableFuture
                .supplyAsync(
                        () -> trafficRBatchController.decideBatch(sceneId, simTime, frame, phaseCandidates),
                        executor
                )
                .whenComplete((decisions, error) -> {
                    synchronized (slot) {
                        slot.inFlight = false;
                        if (error != null) {
                            registerFailure(slot);
                            log.warn(
                                    "Traffic-R batch async decision failed. sid={}, simTime={}, failureStreak={}, fallbackActive={}, error={}",
                                    sid,
                                    simTime,
                                    slot.failureStreak,
                                    slot.fallbackActive,
                                    error.getMessage()
                            );
                            return;
                        }
                        if (decisions == null || decisions.isEmpty()) {
                            registerFailure(slot);
                            log.warn(
                                    "Traffic-R batch async decision returned empty decisions. sid={}, simTime={}, failureStreak={}, fallbackActive={}",
                                    sid,
                                    simTime,
                                    slot.failureStreak,
                                    slot.fallbackActive
                            );
                            return;
                        }
                        registerSuccess(slot);
                        List<ControlDecision> markedDecisions = markAsync(decisions, slot);
                        if (!slot.fallbackActive) {
                            slot.readyDecisions = markedDecisions;
                        }
                    }
                });
    }

    private void registerFailure(DecisionSlot slot) {
        slot.failureStreak += 1;
        slot.successStreak = 0;
        if (slot.failureStreak >= FAILURE_THRESHOLD) {
            slot.fallbackActive = true;
        }
    }

    private void registerSuccess(DecisionSlot slot) {
        slot.failureStreak = 0;
        if (slot.fallbackActive) {
            slot.successStreak += 1;
            if (slot.successStreak >= RECOVERY_THRESHOLD) {
                slot.fallbackActive = false;
                slot.successStreak = 0;
            }
        } else {
            slot.successStreak = Math.min(RECOVERY_THRESHOLD, slot.successStreak + 1);
        }
    }

    private List<ControlDecision> markAsync(List<ControlDecision> decisions, DecisionSlot slot) {
        if (decisions == null) {
            return List.of();
        }
        return decisions.stream()
                .map(decision -> markAsync(decision, slot))
                .toList();
    }

    private ControlDecision markAsync(ControlDecision decision, DecisionSlot slot) {
        return new ControlDecision(
                decision.intersectionId(),
                decision.controllerType(),
                decision.phaseIndex(),
                decision.phaseCode(),
                decision.durationSec(),
                decision.confidence(),
                decision.reason(),
                mergeMetadata(decision.metadata(), slot, "traffic-r")
        );
    }

    private List<ControlDecision> maxPressureDecisions(
            DecisionSlot slot,
            String sid,
            String sceneId,
            double simTime,
            SimFrameData frame,
            List<PhaseCandidate> phaseCandidates
    ) {
        if (frame.signals() == null) {
            return List.of();
        }
        return frame.signals().stream()
                .filter(Objects::nonNull)
                .map(signal -> maxPressureDecision(slot, sid, sceneId, simTime, frame, phaseCandidates, signal))
                .filter(Objects::nonNull)
                .toList();
    }

    private ControlDecision maxPressureDecision(
            DecisionSlot slot,
            String sid,
            String sceneId,
            double simTime,
            SimFrameData frame,
            List<PhaseCandidate> phaseCandidates,
            SignalStateDto signal
    ) {
        ControlRequest request = new ControlRequest(
                sid,
                sceneId,
                "max-pressure",
                signal.intersectionId(),
                simTime,
                signal.phaseIndex(),
                signal.phaseCode(),
                phaseCandidates,
                frame
        );
        ControlDecision decision = maxPressureController.decide(request);
        return new ControlDecision(
                decision.intersectionId(),
                decision.controllerType(),
                decision.phaseIndex(),
                decision.phaseCode(),
                decision.durationSec(),
                decision.confidence(),
                "Traffic-R fallback active; " + decision.reason(),
                mergeMetadata(decision.metadata(), slot, "max-pressure-fallback")
        );
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> metadata, DecisionSlot slot, String mode) {
        HashMap<String, Object> merged = new HashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.put("async", true);
        merged.put("trafficRDispatchMode", mode);
        merged.put("trafficRFailureStreak", slot.failureStreak);
        merged.put("trafficRSuccessStreak", slot.successStreak);
        merged.put("trafficRFallbackActive", slot.fallbackActive);
        return merged;
    }

    private static class DecisionSlot {
        private boolean inFlight = false;
        private Double lastRequestSimTime;
        private Double lastFallbackSimTime;
        private List<ControlDecision> readyDecisions;
        private int failureStreak = 0;
        private int successStreak = 0;
        private boolean fallbackActive = false;
    }
}
