package com.traffic.roadnet.dto;

public record IntersectionDto(
        String id,
        double x,
        double y,
        Double lng,
        Double lat,
        boolean virtual
) {
}
