package com.traffic.strategy.dto;

import com.traffic.simulation.dto.SimFrameData;

import java.util.List;

public record ControlRequest(
        String sid,
        String sceneId,
        String controllerType,
        String intersectionId,
        double simTime,
        Integer currentPhaseIndex,
        String currentPhaseCode,
        List<PhaseCandidate> phaseCandidates,
        SimFrameData frame
) {
}
