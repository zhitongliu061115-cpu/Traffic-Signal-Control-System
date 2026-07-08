package com.traffic.simulation.service;

import com.traffic.simulation.session.SimulationSessionRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SimulationFrameScheduler {

    private final SimulationSessionRegistry sessionRegistry;
    private final SimulationService simulationService;

    public SimulationFrameScheduler(SimulationSessionRegistry sessionRegistry, SimulationService simulationService) {
        this.sessionRegistry = sessionRegistry;
        this.simulationService = simulationService;
    }

    @Scheduled(fixedDelayString = "${cityflow.frame-poll-interval-ms:1000}")
    public void pollFrames() {
        sessionRegistry.findAll().forEach(simulationService::publishNextFrame);
    }
}
