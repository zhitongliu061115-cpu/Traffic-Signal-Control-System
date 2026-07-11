package com.traffic.simulation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record LaneMovementStateDto(
        @JsonProperty("queue_len")
        int queueLen,
        @JsonProperty("avg_wait_time")
        double avgWaitTime,
        List<Integer> cells
) {
}
