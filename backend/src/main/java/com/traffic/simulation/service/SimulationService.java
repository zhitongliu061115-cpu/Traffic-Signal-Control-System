package com.traffic.simulation.service;

import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.common.exception.BusinessException;
import com.traffic.common.util.TimeUtils;
import com.traffic.simulation.dto.CityFlowCreateSimulationRequest;
import com.traffic.simulation.dto.CreateSimulationRequest;
import com.traffic.simulation.dto.CreateSimulationResponse;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.WsMessage;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.simulation.session.SimulationSessionRegistry;
import com.traffic.simulation.session.SimulationSessionState;
import com.traffic.simulation.websocket.SimulationWebSocketHandler;
import com.traffic.strategy.TrafficSignalControllerType;
import com.traffic.strategy.service.TrafficSignalControllerRegistry;
import com.traffic.strategy.service.StrategyDispatchService;
import org.springframework.stereotype.Service;

@Service
public class SimulationService {

    private final CityFlowClient cityFlowClient;
    private final SimulationSessionRegistry sessionRegistry;
    private final SimulationWebSocketHandler webSocketHandler;
    private final TrafficSignalControllerRegistry controllerRegistry;
    private final StrategyDispatchService strategyDispatchService;

    public SimulationService(
            CityFlowClient cityFlowClient,
            SimulationSessionRegistry sessionRegistry,
            SimulationWebSocketHandler webSocketHandler,
            TrafficSignalControllerRegistry controllerRegistry,
            StrategyDispatchService strategyDispatchService
    ) {
        this.cityFlowClient = cityFlowClient;
        this.sessionRegistry = sessionRegistry;
        this.webSocketHandler = webSocketHandler;
        this.controllerRegistry = controllerRegistry;
        this.strategyDispatchService = strategyDispatchService;
    }

    public CreateSimulationResponse createSimulation(CreateSimulationRequest request) {
        String controllerType = TrafficSignalControllerType.fromCode(request.controllerType()).code();
        controllerRegistry.get(controllerType);
        var cityFlowResponse = cityFlowClient.createSimulation(
                new CityFlowCreateSimulationRequest(request.sceneId(), request.speed())
        );
        sessionRegistry.register(cityFlowResponse.sid(), cityFlowResponse.sceneId(), controllerType);
        return new CreateSimulationResponse(
                cityFlowResponse.sid(),
                cityFlowResponse.sceneId(),
                cityFlowResponse.status(),
                controllerType
        );
    }

    public void start(String sid) {
        SimulationRuntimeSession session = findSession(sid);
        session.setState(SimulationSessionState.RUNNING);
    }

    public void pause(String sid) {
        SimulationRuntimeSession session = findSession(sid);
        session.setState(SimulationSessionState.PAUSED);
    }

    public void stop(String sid) {
        SimulationRuntimeSession session = findSession(sid);
        session.setState(SimulationSessionState.FINISHED);
    }

    public void publishNextFrame(SimulationRuntimeSession session) {
        if (session.getState() != SimulationSessionState.RUNNING) {
            return;
        }
        // Spring Boot owns WebSocket delivery; Python only advances CityFlow and returns frame data.
        SimFrameData frameData = cityFlowClient.nextFrame(session.getSid());
        strategyDispatchService.decideAndApply(session, frameData);
        long seq = session.nextSequence();
        session.setSimTime(frameData.simTime());
        WsMessage<SimFrameData> message = new WsMessage<>(
                "1.0",
                "sim.frame",
                session.getSid(),
                seq,
                session.getSimTime(),
                TimeUtils.nowRfc3339(),
                frameData
        );
        webSocketHandler.publish(session.getSid(), message);
    }

    private SimulationRuntimeSession findSession(String sid) {
        return sessionRegistry.find(sid)
                .orElseThrow(() -> new BusinessException("simulation session not found: " + sid));
    }
}
