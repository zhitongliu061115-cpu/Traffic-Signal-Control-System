package com.traffic.strategy.rl;

import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RlController implements TrafficSignalController {

    @Override
    public String controllerType() {
        return "traffic-r";
    }

    @Override
    public ControlDecision decide(ControlRequest request) {
        Integer currentPhase = request.currentPhaseIndex();
        int phaseIndex = currentPhase == null ? 1 : currentPhase;
        String phaseCode = request.currentPhaseCode() == null ? "ETWT" : request.currentPhaseCode();
        return new ControlDecision(
                request.intersectionId(),
                controllerType(),
                phaseIndex,
                phaseCode,
                10,
                0.0,
                "Traffic-R cloud model is not connected yet; keep current phase as a safe placeholder decision",
                Map.of("status", "pending_external_model")
        );
    }
}
