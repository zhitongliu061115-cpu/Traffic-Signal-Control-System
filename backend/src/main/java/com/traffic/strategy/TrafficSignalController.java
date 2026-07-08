package com.traffic.strategy;

import com.traffic.strategy.dto.ControlDecision;
import com.traffic.strategy.dto.ControlRequest;

public interface TrafficSignalController {

    String controllerType();

    ControlDecision decide(ControlRequest request);
}
