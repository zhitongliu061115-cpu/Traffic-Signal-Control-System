package com.traffic.strategy.safety;

import com.traffic.strategy.dto.ControlDecision;
import java.util.List;

public record SafetyReviewResult(
        List<ControlDecision> safeDecisions,
        List<ControlDecision> auditDecisions
) {
}
