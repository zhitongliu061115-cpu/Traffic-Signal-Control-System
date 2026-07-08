package com.traffic.strategy.dto;

public record ControlRequest(
        String sid,
        String intersectionId,
        double simTime
) {
}
