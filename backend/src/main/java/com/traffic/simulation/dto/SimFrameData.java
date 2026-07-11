package com.traffic.simulation.dto;

import java.util.List;
import java.util.Map;

public record SimFrameData(
        double simTime,
        String status,
        List<VehicleStateDto> vehicles,
        List<RoadStateDto> roads,
        Map<String, IntersectionLaneStateDto> laneStates,
        List<IntersectionStateDto> intersections,
        List<SignalStateDto> signals,
        SimulationMetricsDto metrics,
        List<EvEventDto> evEvents,
        List<EvStatusDto> evStatus
) {
}
