package com.traffic.strategy.fixed;

import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;
import org.springframework.stereotype.Component;

@Component
public class FixedTimeController implements TrafficSignalController {

    @Override
    public String controllerType() {
        return "FixedTime";
    }

    @Override
    public ControlDecision decide(ControlRequest request) {
        throw new UnsupportedOperationException("FixedTime control is not implemented in today's visualization phase.");
    }
}
