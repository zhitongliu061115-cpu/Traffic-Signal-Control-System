package com.traffic.strategy.rl.dto;

import com.traffic.simulation.dto.IntersectionLaneStateDto;

import java.util.List;
import java.util.Map;

public record TrafficRObservation(
        List<TrafficRRoadObservation> roads,
        Map<String, IntersectionLaneStateDto> laneStates,
        Map<String, Object> metrics
) {
}
