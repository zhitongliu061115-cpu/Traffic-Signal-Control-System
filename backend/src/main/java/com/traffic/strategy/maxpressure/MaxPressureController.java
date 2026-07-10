package com.traffic.strategy.maxpressure;

import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                    2,
                    "ETWT",
                    10,
                    0.0,
                    "max-pressure fallback uses default phase because no phase candidates were provided",
                    Map.of("status", "fallback_no_phase_candidates")
            );
        }

        var selected = phases.stream()
                .filter(Objects::nonNull)
                .max((left, right) -> Integer.compare(
                        lanePressure(request, left.phaseCode()),
                        lanePressure(request, right.phaseCode())
                ))
                .orElse(phases.get(0));
        int pressure = lanePressure(request, selected.phaseCode());
        return new ControlDecision(
                request.intersectionId(),
                controllerType(),
                selected.phaseIndex(),
                selected.phaseCode(),
                10,
                0.6,
                "max-pressure baseline selects the phase with the highest lane-level queue pressure",
                Map.of("pressure", pressure, "sourceState", "lane-level")
        );
    }

    private int lanePressure(ControlRequest request, String phaseCode) {
        if (phaseCode == null || phaseCode.length() != 4 || request.frame() == null || request.frame().laneStates() == null) {
            return roadLevelPressure(request);
        }
        var intersectionState = request.frame().laneStates().get(request.intersectionId());
        if (intersectionState == null || intersectionState.lanes() == null) {
            return roadLevelPressure(request);
        }
        return movementPressure(intersectionState.lanes().get(phaseCode.substring(0, 2)))
                + movementPressure(intersectionState.lanes().get(phaseCode.substring(2, 4)));
    }

    private int movementPressure(com.traffic.simulation.dto.LaneMovementStateDto lane) {
        if (lane == null) {
            return 0;
        }
        List<Integer> cells = lane.cells() == null ? List.of() : lane.cells();
        int cellPressure = 0;
        for (int index = 0; index < cells.size(); index++) {
            int weight = switch (index) {
                case 0 -> 4;
                case 1 -> 3;
                case 2 -> 2;
                default -> 1;
            };
            cellPressure += Math.max(0, cells.get(index)) * weight;
        }
        return lane.queueLen() * 10 + cellPressure;
    }

    private int roadLevelPressure(ControlRequest request) {
        return request.frame() == null || request.frame().roads() == null
                ? 0
                : request.frame().roads().stream().mapToInt(road -> road.queueCount() + road.vehicleCount()).sum();
    }
}
