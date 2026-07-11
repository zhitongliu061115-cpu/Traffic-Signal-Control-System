package com.traffic.intersection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateIntersectionStatusRequest(
        @NotBlank
        @Pattern(regexp = "online|maintenance|offline", message = "must be online, maintenance, or offline")
        String status
) {
}
