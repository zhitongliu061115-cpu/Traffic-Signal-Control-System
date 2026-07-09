package com.traffic.cityflow.dto;

import java.util.List;

public record ApplyControlActionsResponse(
        String sid,
        List<AppliedControlAction> applied
) {
}
