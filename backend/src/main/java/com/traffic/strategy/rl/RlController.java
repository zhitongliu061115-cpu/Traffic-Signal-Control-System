package com.traffic.strategy.rl;

import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;

public class RlController implements TrafficSignalController {

    @Override
    public String controllerType() {
        return "RL";
    }

    @Override
    public ControlDecision decide(ControlRequest request) {
        throw new UnsupportedOperationException("RL control is reserved until LightGPT or another model is selected.");
    }
}
