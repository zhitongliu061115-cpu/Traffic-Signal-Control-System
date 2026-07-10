package com.traffic.strategy.fixed;

import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import org.springframework.stereotype.Component;

@Component
public class FixedTimeController implements TrafficSignalController {

    @Override
    public String controllerType() {
        return "fixed-time";
    }

    @Override
    public ControlDecision decide(ControlRequest request) {
        var phases = request.phaseCandidates();
        if (phases == null || phases.isEmpty()) {
            return new ControlDecision(
                    request.intersectionId(),
                    controllerType(),
                    2,
                    "ETWT",
                    10,
                    1.0,
                    "fixed-time fallback uses default phase because no phase candidates were provided",
                    java.util.Map.of("status", "fallback_no_phase_candidates")
            );
        }
        int index = (int) Math.floor(request.simTime() / 10.0) % phases.size();
        var selected = phases.get(index);
        return new ControlDecision(
                request.intersectionId(),
                controllerType(),
                selected.phaseIndex(),
                selected.phaseCode(),
                10,
                1.0,
                "fixed-time selects phases by a 10-second cycle",
                java.util.Map.of("cycleIndex", index)
        );
    }
}
