package com.traffic.strategy.maxpressure;

import com.traffic.strategy.TrafficSignalController;
import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;

public class MaxPressureController implements TrafficSignalController {

    @Override
    public String controllerType() {
        return "MaxPressure";
    }

    @Override
    public ControlDecision decide(ControlRequest request) {
        throw new UnsupportedOperationException("MaxPressure control is reserved for fallback strategy integration.");
    }
}
