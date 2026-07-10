package com.traffic.simulation.dto;

import jakarta.validation.constraints.NotBlank;

public record EvDispatchRequest(
        String evId,
        String evType,
        Integer priority,
        @NotBlank String startIntersection,
        @NotBlank String endIntersection
) {
}
