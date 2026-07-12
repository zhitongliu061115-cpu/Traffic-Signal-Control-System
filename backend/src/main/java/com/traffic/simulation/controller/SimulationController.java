package com.traffic.simulation.controller;

import com.traffic.common.response.ApiResponse;
import com.traffic.simulation.dto.CreateSimulationRequest;
import com.traffic.simulation.dto.CreateSimulationResponse;
import com.traffic.simulation.service.SimulationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/simulations")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping
    public ApiResponse<CreateSimulationResponse> create(@Valid @RequestBody CreateSimulationRequest request) {
        return ApiResponse.ok(simulationService.createSimulation(request));
    }

    @PostMapping("/{sid}/start")
    public ApiResponse<Void> start(@PathVariable String sid) {
        simulationService.start(sid);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{sid}/pause")
    public ApiResponse<Void> pause(@PathVariable String sid) {
        simulationService.pause(sid);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{sid}/stop")
    public ApiResponse<Void> stop(@PathVariable String sid) {
        simulationService.stop(sid);
        return ApiResponse.ok(null);
    }

}
