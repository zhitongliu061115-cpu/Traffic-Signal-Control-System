package com.traffic.cityflow.dto;

import com.traffic.strategy.dto.ControlDecision;

import java.util.List;

public record ApplyControlActionsRequest(
        String source,
        double simTime,
        List<ControlDecision> decisions
) {
}
