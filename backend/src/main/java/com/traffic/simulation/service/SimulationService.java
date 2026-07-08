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
import org.springframework.stereotype.Service;

@Service
public class SimulationService {

    private final CityFlowClient cityFlowClient;
    private final SimulationSessionRegistry sessionRegistry;
    private final SimulationWebSocketHandler webSocketHandler;

    public SimulationService(
            CityFlowClient cityFlowClient,
            SimulationSessionRegistry sessionRegistry,
            SimulationWebSocketHandler webSocketHandler
    ) {
        this.cityFlowClient = cityFlowClient;
        this.sessionRegistry = sessionRegistry;
        this.webSocketHandler = webSocketHandler;
    }

    public CreateSimulationResponse createSimulation(CreateSimulationRequest request) {
        var cityFlowResponse = cityFlowClient.createSimulation(
                new CityFlowCreateSimulationRequest(request.sceneId(), request.speed())
        );
        sessionRegistry.register(cityFlowResponse.sid(), cityFlowResponse.sceneId());
        return new CreateSimulationResponse(cityFlowResponse.sid(), cityFlowResponse.sceneId(), cityFlowResponse.status());
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
        long seq = session.nextSequence();
        session.setSimTime(seq);
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
