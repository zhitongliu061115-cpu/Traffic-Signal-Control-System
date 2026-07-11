package com.traffic.intersection.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record IntersectionResponse(
        UUID id,
        String code,
        String name,
        String district,
        BigDecimal longitude,
        BigDecimal latitude,
        String status,
        String metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
