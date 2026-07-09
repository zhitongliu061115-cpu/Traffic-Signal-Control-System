package com.traffic.simulation.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSimulationRequest(
        @NotBlank String sceneId,
        Double speed,
        String controllerType
) {
}
