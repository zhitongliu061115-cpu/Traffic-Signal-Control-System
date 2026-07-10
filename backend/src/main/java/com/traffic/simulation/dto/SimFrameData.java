package com.traffic.simulation.dto;

import java.util.List;

public record SimFrameData(
        double simTime,
        List<VehicleStateDto> vehicles,
        List<RoadStateDto> roads,
        List<IntersectionStateDto> intersections,
        List<SignalStateDto> signals,
        SimulationMetricsDto metrics,
        List<EvEventDto> evEvents,
        List<EvStatusDto> evStatus
) {
}
