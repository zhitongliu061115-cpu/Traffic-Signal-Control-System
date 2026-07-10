package com.traffic.strategy.dto;

import com.traffic.simulation.dto.SimFrameData;

import java.util.List;

public record AppliedControlResult(
        List<ControlDecision> decisions,
        SimFrameData frameAfterApply
) {
}
