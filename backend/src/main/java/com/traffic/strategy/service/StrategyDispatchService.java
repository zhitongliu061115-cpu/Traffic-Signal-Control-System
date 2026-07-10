package com.traffic.strategy.service;

import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.cityflow.dto.AppliedControlAction;
import com.traffic.cityflow.dto.ApplyControlActionsRequest;
import com.traffic.cityflow.dto.ApplyControlActionsResponse;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.strategy.dto.AppliedControlResult;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import com.traffic.strategy.dto.PhaseCandidate;
import com.traffic.strategy.phase.JinanPhaseMapper;
import com.traffic.strategy.rl.TrafficRAsyncDecisionService;
import com.traffic.strategy.rl.TrafficRDecisionAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StrategyDispatchService {

    private static final Logger log = LoggerFactory.getLogger(StrategyDispatchService.class);

    private static final List<PhaseCandidate> JINAN_PHASE_CANDIDATES = List.of(
            new PhaseCandidate(JinanPhaseMapper.cityflowPhaseIndex("ETWT"), "ETWT", List.of()),
            new PhaseCandidate(JinanPhaseMapper.cityflowPhaseIndex("NTST"), "NTST", List.of()),
            new PhaseCandidate(JinanPhaseMapper.cityflowPhaseIndex("ELWL"), "ELWL", List.of()),
            new PhaseCandidate(JinanPhaseMapper.cityflowPhaseIndex("NLSL"), "NLSL", List.of())
    );
    private static final double SYNCHRONOUS_DECISION_INTERVAL_SEC = 10.0;

    private final CityFlowClient cityFlowClient;
    private final TrafficSignalControllerRegistry controllerRegistry;
    private final TrafficRAsyncDecisionService trafficRAsyncDecisionService;
    private final TrafficRDecisionAuditLogger auditLogger;
    private final Map<String, Double> lastSynchronousDecisionSimTime = new ConcurrentHashMap<>();
    private final Set<String> pendingActionKeys = ConcurrentHashMap.newKeySet();
    private final ExecutorService actionExecutor = Executors.newFixedThreadPool(2);

    public StrategyDispatchService(
            CityFlowClient cityFlowClient,
            TrafficSignalControllerRegistry controllerRegistry,
            TrafficRAsyncDecisionService trafficRAsyncDecisionService,
            TrafficRDecisionAuditLogger auditLogger
    ) {
        this.cityFlowClient = cityFlowClient;
        this.controllerRegistry = controllerRegistry;
        this.trafficRAsyncDecisionService = trafficRAsyncDecisionService;
        this.auditLogger = auditLogger;
    }

    public AppliedControlResult decideAndApply(SimulationRuntimeSession session, SimFrameData frame) {
        List<ControlDecision> decisions = decide(session, frame);
        decisions = changedDecisions(decisions, frame);
        if (!decisions.isEmpty()) {
            List<ControlDecision> submittedDecisions = decisions.stream()
                    .filter(decision -> pendingActionKeys.add(actionKey(session.getSid(), decision)))
                    .toList();
            if (submittedDecisions.isEmpty()) {
                return new AppliedControlResult(List.of(), null);
            }
            submitApplyActions(session, frame, submittedDecisions);
            return new AppliedControlResult(markPending(submittedDecisions), null);
        }
        return new AppliedControlResult(List.of(), null);
    }

    private void submitApplyActions(SimulationRuntimeSession session, SimFrameData frame, List<ControlDecision> decisions) {
        actionExecutor.submit(() -> {
            try {
                auditLogger.append("backend.cityflow-actions.request", Map.of(
                        "sid", session.getSid(),
                        "sceneId", session.getSceneId(),
                        "controllerType", session.getControllerType(),
                        "simTime", frame.simTime(),
                        "async", true,
                        "decisions", decisions
                ));
                ApplyControlActionsResponse response = cityFlowClient.applyControlActions(
                        session.getSid(),
                        new ApplyControlActionsRequest(session.getControllerType(), frame.simTime(), decisions)
                );
                List<ControlDecision> appliedDecisions = markApplied(decisions, response);
                auditLogger.append("backend.cityflow-actions.response", Map.of(
                        "sid", session.getSid(),
                        "sceneId", session.getSceneId(),
                        "controllerType", session.getControllerType(),
                        "simTime", frame.simTime(),
                        "async", true,
                        "response", response,
                        "appliedDecisions", appliedDecisions
                ));
            } catch (RuntimeException ex) {
                log.warn(
                        "failed to apply control actions to CityFlow asynchronously; keep publishing frames. sid={}, controllerType={}, simTime={}, error={}",
                        session.getSid(),
                        session.getControllerType(),
                        frame.simTime(),
                        ex.getMessage()
                );
            } finally {
                decisions.forEach(decision -> pendingActionKeys.remove(actionKey(session.getSid(), decision)));
            }
        });
    }

    private List<ControlDecision> markPending(List<ControlDecision> decisions) {
        return decisions.stream()
                .map(this::markPending)
                .toList();
    }

    private ControlDecision markPending(ControlDecision decision) {
        Map<String, Object> metadata = new HashMap<>();
        if (decision.metadata() != null) {
            metadata.putAll(decision.metadata());
        }
        metadata.put("cityflowApplyPending", true);
        metadata.put("cityflowApplied", false);
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

    private String actionKey(String sid, ControlDecision decision) {
        return sid + ":" + decision.intersectionId() + ":" + decision.phaseIndex();
    }

    private List<ControlDecision> changedDecisions(List<ControlDecision> decisions, SimFrameData frame) {
        if (decisions == null || decisions.isEmpty() || frame.signals() == null || frame.signals().isEmpty()) {
            return List.of();
        }
        Map<String, Integer> currentPhaseByIntersection = frame.signals().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SignalStateDto::intersectionId, SignalStateDto::phaseIndex, (left, ignored) -> left));
        return decisions.stream()
                .filter(Objects::nonNull)
                .filter(decision -> !Objects.equals(currentPhaseByIntersection.get(decision.intersectionId()), decision.phaseIndex()))
                .toList();
    }

    private List<ControlDecision> markApplied(List<ControlDecision> decisions, ApplyControlActionsResponse response) {
        if (response == null || response.applied() == null || response.applied().isEmpty()) {
            return List.of();
        }
        Map<String, AppliedControlAction> appliedByIntersection = response.applied().stream()
                .filter(Objects::nonNull)
                .filter(action -> action.intersectionId() != null)
                .collect(Collectors.toMap(AppliedControlAction::intersectionId, Function.identity(), (left, ignored) -> left));
        return decisions.stream()
                .filter(Objects::nonNull)
                .filter(decision -> appliedByIntersection.containsKey(decision.intersectionId()))
                .map(decision -> markApplied(decision, appliedByIntersection.get(decision.intersectionId())))
                .toList();
    }

    private ControlDecision markApplied(ControlDecision decision, AppliedControlAction action) {
        Map<String, Object> metadata = new HashMap<>();
        if (decision.metadata() != null) {
            metadata.putAll(decision.metadata());
        }
        metadata.put("cityflowApplied", true);
        metadata.put("cityflowApplyStatus", action.status());
        metadata.put("cityflowAppliedPhaseIndex", action.phaseIndex());
        metadata.put("cityflowAppliedPhaseId", action.cityflowPhaseId());
        return new ControlDecision(
                decision.intersectionId(),
                decision.controllerType(),
                action.phaseIndex(),
                action.phaseCode() == null ? decision.phaseCode() : action.phaseCode(),
                decision.durationSec(),
                decision.confidence(),
                decision.reason(),
                metadata
        );
    }

    private List<ControlDecision> decide(SimulationRuntimeSession session, SimFrameData frame) {
        if (frame.signals() == null || frame.signals().isEmpty()) {
            return List.of();
        }
        if ("traffic-r".equals(session.getControllerType())) {
            return trafficRAsyncDecisionService
                    .pollDecision(session.getSid(), session.getSceneId(), frame.simTime(), frame, JINAN_PHASE_CANDIDATES)
                    .orElse(List.of());
        }
        if (!shouldRunSynchronousController(session, frame.simTime())) {
            return List.of();
        }
        lastSynchronousDecisionSimTime.put(session.getSid(), frame.simTime());
        var controller = controllerRegistry.get(session.getControllerType());
        return frame.signals().stream()
                .filter(Objects::nonNull)
                .map(signal -> {
                    try {
                        ControlRequest request = toRequest(session, frame, signal);
                        return controller.decide(request);
                    } catch (RuntimeException ex) {
                        log.warn(
                                "failed to create control decision; skip this signal. sid={}, controllerType={}, intersectionId={}, simTime={}, error={}",
                                session.getSid(),
                                session.getControllerType(),
                                signal.intersectionId(),
                                frame.simTime(),
                                ex.getMessage()
                        );
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean shouldRunSynchronousController(SimulationRuntimeSession session, double simTime) {
        Double lastDecisionSimTime = lastSynchronousDecisionSimTime.get(session.getSid());
        return lastDecisionSimTime == null || simTime - lastDecisionSimTime >= SYNCHRONOUS_DECISION_INTERVAL_SEC;
    }

    private ControlRequest toRequest(SimulationRuntimeSession session, SimFrameData frame, SignalStateDto signal) {
        return new ControlRequest(
                session.getSid(),
                session.getSceneId(),
                session.getControllerType(),
                signal.intersectionId(),
                frame.simTime(),
                signal.phaseIndex(),
                signal.phaseCode(),
                JINAN_PHASE_CANDIDATES,
                frame
        );
    }
}
