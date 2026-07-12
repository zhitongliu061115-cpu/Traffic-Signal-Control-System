package com.traffic.strategy.safety;

import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.PhaseCandidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SafetyLayerService {

    private static final int MIN_DURATION_SEC = 3;
    private static final int MAX_DURATION_SEC = 120;
    private static final double MIN_PHASE_HOLD_SEC = 5.0;

    private final Map<String, Integer> observedPhaseBySignal = new ConcurrentHashMap<>();
    private final Map<String, Double> phaseChangedAtBySignal = new ConcurrentHashMap<>();

    public SafetyReviewResult review(
            SimulationRuntimeSession session,
            SimFrameData frame,
            List<ControlDecision> proposedDecisions,
            List<PhaseCandidate> phaseCandidates
    ) {
        if (session == null || frame == null) {
            return new SafetyReviewResult(List.of(), List.of());
        }

        Map<String, SignalStateDto> currentSignals = currentSignals(frame);
        updateObservedPhaseState(session.getSid(), frame.simTime(), currentSignals);
        if (proposedDecisions == null || proposedDecisions.isEmpty()) {
            return new SafetyReviewResult(List.of(), List.of());
        }
        Set<Integer> legalPhaseIndexes = phaseCandidates == null
                ? Set.of()
                : phaseCandidates.stream()
                .filter(Objects::nonNull)
                .map(PhaseCandidate::phaseIndex)
                .collect(Collectors.toSet());
        Map<Integer, String> phaseCodeByIndex = phaseCandidates == null
                ? Map.of()
                : phaseCandidates.stream()
                .filter(Objects::nonNull)
                .filter(phase -> StringUtils.hasText(phase.phaseCode()))
                .collect(Collectors.toMap(PhaseCandidate::phaseIndex, PhaseCandidate::phaseCode, (left, ignored) -> left));

        List<ControlDecision> safe = new ArrayList<>();
        List<ControlDecision> audit = new ArrayList<>();
        for (ControlDecision decision : proposedDecisions) {
            Review review = reviewOne(session, frame, decision, currentSignals, legalPhaseIndexes, phaseCodeByIndex);
            if (review.safeForCityFlow()) {
                safe.add(review.decision());
            } else {
                audit.add(review.decision());
            }
        }
        return new SafetyReviewResult(List.copyOf(safe), List.copyOf(audit));
    }

    public void releaseSession(String sid) {
        if (!StringUtils.hasText(sid)) {
            return;
        }
        observedPhaseBySignal.keySet().removeIf(key -> key.startsWith(sid + ":"));
        phaseChangedAtBySignal.keySet().removeIf(key -> key.startsWith(sid + ":"));
    }

    private Review reviewOne(
            SimulationRuntimeSession session,
            SimFrameData frame,
            ControlDecision decision,
            Map<String, SignalStateDto> currentSignals,
            Set<Integer> legalPhaseIndexes,
            Map<Integer, String> phaseCodeByIndex
    ) {
        if (decision == null || !StringUtils.hasText(decision.intersectionId())) {
            return new Review(reject(decision, null, "phase_validity", "reject", "决策缺少 intersectionId"), false);
        }

        SignalStateDto current = currentSignals.get(decision.intersectionId());
        if (current == null) {
            return new Review(reject(decision, null, "intersection_validity", "reject", "当前 CityFlow 帧不存在该路口信号"), false);
        }

        if (!legalPhaseIndexes.isEmpty() && !legalPhaseIndexes.contains(decision.phaseIndex())) {
            return new Review(fallbackToCurrent(decision, current, "phase_validity",
                    "非法相位：" + decision.phaseIndex() + " 不在候选相位 " + legalPhaseIndexes + " 中"), false);
        }

        String canonicalPhaseCode = phaseCodeByIndex.get(decision.phaseIndex());
        if (StringUtils.hasText(canonicalPhaseCode)
                && StringUtils.hasText(decision.phaseCode())
                && !canonicalPhaseCode.equals(decision.phaseCode())) {
            return new Review(fallbackToCurrent(decision, current, "phase_mapping",
                    "相位编号与业务编码不一致：phaseIndex=" + decision.phaseIndex()
                            + ", phaseCode=" + decision.phaseCode()
                            + ", expected=" + canonicalPhaseCode), false);
        }

        if (decision.durationSec() != null
                && (decision.durationSec() < MIN_DURATION_SEC || decision.durationSec() > MAX_DURATION_SEC)) {
            return new Review(fallbackToCurrent(decision, current, "duration_bounds",
                    "相位持续时间越界：" + decision.durationSec()
                            + "s，允许范围 " + MIN_DURATION_SEC + "-" + MAX_DURATION_SEC + "s"), false);
        }

        if (current.phaseIndex() != decision.phaseIndex()) {
            double heldSeconds = frame.simTime() - phaseChangedAtBySignal.getOrDefault(signalKey(session.getSid(), current.intersectionId()), frame.simTime());
            if (heldSeconds >= 0 && heldSeconds < MIN_PHASE_HOLD_SEC) {
                return new Review(fallbackToCurrent(decision, current, "min_green",
                        "当前相位保持时间 " + round(heldSeconds) + "s，小于最小保持时间 " + MIN_PHASE_HOLD_SEC + "s"), false);
            }
        }

        ControlDecision accepted = withSafetyMetadata(decision, true, List.of(new SafetyCheckEvent(
                "safety_gate",
                "allow",
                current.phaseIndex(),
                current.phaseCode(),
                decision.phaseIndex(),
                StringUtils.hasText(canonicalPhaseCode) ? canonicalPhaseCode : decision.phaseCode(),
                "安全层校验通过"
        )));
        return new Review(accepted, true);
    }

    private ControlDecision fallbackToCurrent(ControlDecision proposed, SignalStateDto current, String constraintType, String reason) {
        ControlDecision fallback = new ControlDecision(
                proposed.intersectionId(),
                proposed.controllerType(),
                current.phaseIndex(),
                current.phaseCode(),
                proposed.durationSec(),
                0.0,
                "Safety layer fallback to current phase: " + reason,
                proposed.metadata()
        );
        return withSafetyMetadata(fallback, false, List.of(new SafetyCheckEvent(
                constraintType,
                "fallback_current_phase",
                current.phaseIndex(),
                current.phaseCode(),
                current.phaseIndex(),
                current.phaseCode(),
                reason
        )), proposed);
    }

    private ControlDecision reject(ControlDecision proposed, SignalStateDto current, String constraintType, String action, String reason) {
        ControlDecision base = proposed == null
                ? new ControlDecision("unknown", "unknown", 0, "unknown", 0, 0.0, reason, Map.of())
                : proposed;
        return withSafetyMetadata(base, false, List.of(new SafetyCheckEvent(
                constraintType,
                action,
                current == null ? null : current.phaseIndex(),
                current == null ? null : current.phaseCode(),
                base.phaseIndex(),
                base.phaseCode(),
                reason
        )), proposed);
    }

    private ControlDecision withSafetyMetadata(ControlDecision decision, boolean allowed, List<SafetyCheckEvent> events) {
        return withSafetyMetadata(decision, allowed, events, decision);
    }

    private ControlDecision withSafetyMetadata(
            ControlDecision decision,
            boolean allowed,
            List<SafetyCheckEvent> events,
            ControlDecision originalDecision
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (decision.metadata() != null) {
            metadata.putAll(decision.metadata());
        }
        metadata.put("safetyChecked", true);
        metadata.put("safetyAllowed", allowed);
        metadata.put("safetyAction", allowed ? "allow" : "fallback_or_reject");
        if (!allowed) {
            metadata.put("safetyRejected", true);
        }
        if (originalDecision != null) {
            metadata.put("safetyOriginalPhaseIndex", originalDecision.phaseIndex());
            metadata.put("safetyOriginalPhaseCode", originalDecision.phaseCode());
        }
        metadata.put("safetyEvents", events.stream().map(this::eventMap).toList());
        return new ControlDecision(
                decision.intersectionId(),
                decision.controllerType(),
                decision.phaseIndex(),
                decision.phaseCode(),
                decision.durationSec(),
                decision.confidence(),
                decision.reason(),
                metadata
        );
    }

    private Map<String, Object> eventMap(SafetyCheckEvent event) {
        Map<String, Object> value = new HashMap<>();
        value.put("constraintType", event.constraintType());
        value.put("action", event.action());
        value.put("beforePhaseIndex", event.beforePhaseIndex());
        value.put("beforePhaseCode", event.beforePhaseCode());
        value.put("afterPhaseIndex", event.afterPhaseIndex());
        value.put("afterPhaseCode", event.afterPhaseCode());
        value.put("reason", event.reason());
        return value;
    }

    private Map<String, SignalStateDto> currentSignals(SimFrameData frame) {
        if (frame.signals() == null) {
            return Map.of();
        }
        return frame.signals().stream()
                .filter(Objects::nonNull)
                .filter(signal -> StringUtils.hasText(signal.intersectionId()))
                .collect(Collectors.toMap(SignalStateDto::intersectionId, signal -> signal, (left, ignored) -> left));
    }

    private void updateObservedPhaseState(String sid, double simTime, Map<String, SignalStateDto> currentSignals) {
        currentSignals.forEach((intersectionId, signal) -> {
            String key = signalKey(sid, intersectionId);
            Integer previous = observedPhaseBySignal.put(key, signal.phaseIndex());
            if (previous == null) {
                phaseChangedAtBySignal.putIfAbsent(key, Math.max(0.0, simTime - MIN_PHASE_HOLD_SEC));
            } else if (previous != signal.phaseIndex()) {
                phaseChangedAtBySignal.put(key, simTime);
            }
        });
    }

    private String signalKey(String sid, String intersectionId) {
        return sid + ":" + intersectionId;
    }

    private String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private record Review(ControlDecision decision, boolean safeForCityFlow) {
    }
}
