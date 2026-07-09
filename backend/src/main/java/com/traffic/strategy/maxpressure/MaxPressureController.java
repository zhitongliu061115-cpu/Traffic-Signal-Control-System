package com.traffic.strategy.maxpressure;

import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MaxPressureController implements TrafficSignalController {

    @Override
    public String controllerType() {
        return "max-pressure";
    }

    @Override
    public ControlDecision decide(ControlRequest request) {
        var phases = request.phaseCandidates();
        if (phases == null || phases.isEmpty()) {
            return new ControlDecision(
                    request.intersectionId(),
                    controllerType(),
                    1,
                    "ETWT",
                    10,
                    0.0,
                    "max-pressure fallback uses default phase because no phase candidates were provided",
                    Map.of("status", "fallback_no_phase_candidates")
            );
        }

        int roadPressure = request.frame() == null || request.frame().roads() == null
                ? 0
                : request.frame().roads().stream().mapToInt(road -> road.queueCount() + road.vehicleCount()).sum();
        int selectedIndex = phases.isEmpty() ? 0 : Math.floorMod(roadPressure, phases.size());
        var selected = phases.get(selectedIndex);
        return new ControlDecision(
                request.intersectionId(),
                controllerType(),
                selected.phaseIndex(),
                selected.phaseCode(),
                10,
                0.6,
                "max-pressure baseline selects a phase from current road queue and vehicle pressure",
                Map.of("pressure", roadPressure, "selectedIndex", selectedIndex)
        );
    }
}
