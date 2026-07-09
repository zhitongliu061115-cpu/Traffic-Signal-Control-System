package com.traffic.emergency.dto;

import jakarta.validation.constraints.NotNull;

public record CoordDTO(
        @NotNull double x,
        @NotNull double y
) {
}
