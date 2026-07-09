package com.traffic.strategy.service;

import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.cityflow.dto.ApplyControlActionsRequest;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import com.traffic.strategy.dto.PhaseCandidate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class StrategyDispatchService {

    private static final List<PhaseCandidate> JINAN_PHASE_CANDIDATES = List.of(
            new PhaseCandidate(1, "ETWT", List.of()),
            new PhaseCandidate(2, "NTST", List.of()),
            new PhaseCandidate(3, "ELWL", List.of()),
            new PhaseCandidate(4, "NLSL", List.of()),
            new PhaseCandidate(5, null, List.of()),
            new PhaseCandidate(6, null, List.of()),
            new PhaseCandidate(7, null, List.of()),
            new PhaseCandidate(8, null, List.of()),
            new PhaseCandidate(9, null, List.of())
    );

    private final CityFlowClient cityFlowClient;
    private final TrafficSignalControllerRegistry controllerRegistry;

    public StrategyDispatchService(CityFlowClient cityFlowClient, TrafficSignalControllerRegistry controllerRegistry) {
        this.cityFlowClient = cityFlowClient;
        this.controllerRegistry = controllerRegistry;
    }

    public List<ControlDecision> decideAndApply(SimulationRuntimeSession session, SimFrameData frame) {
        List<ControlDecision> decisions = decide(session, frame);
        if (!decisions.isEmpty()) {
            cityFlowClient.applyControlActions(
                    session.getSid(),
                    new ApplyControlActionsRequest(session.getControllerType(), frame.simTime(), decisions)
            );
        }
        return decisions;
    }

    private List<ControlDecision> decide(SimulationRuntimeSession session, SimFrameData frame) {
        if (frame.signals() == null || frame.signals().isEmpty()) {
            return List.of();
        }
        var controller = controllerRegistry.get(session.getControllerType());
        return frame.signals().stream()
                .filter(Objects::nonNull)
                .map(signal -> controller.decide(toRequest(session, frame, signal)))
                .toList();
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
