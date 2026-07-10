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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final CityFlowClient cityFlowClient;
    private final SimulationSessionRegistry sessionRegistry;
    private final SimulationWebSocketHandler webSocketHandler;
    private final TrafficSignalControllerRegistry controllerRegistry;
    private final StrategyDispatchService strategyDispatchService;
    private final SimulationFrameTimingLogger frameTimingLogger;

    public SimulationService(
            CityFlowClient cityFlowClient,
            SimulationSessionRegistry sessionRegistry,
            SimulationWebSocketHandler webSocketHandler,
            TrafficSignalControllerRegistry controllerRegistry,
            StrategyDispatchService strategyDispatchService,
            SimulationFrameTimingLogger frameTimingLogger
    ) {
        this.cityFlowClient = cityFlowClient;
        this.sessionRegistry = sessionRegistry;
        this.webSocketHandler = webSocketHandler;
        this.controllerRegistry = controllerRegistry;
        this.strategyDispatchService = strategyDispatchService;
        this.frameTimingLogger = frameTimingLogger;
    }

    public CreateSimulationResponse createSimulation(CreateSimulationRequest request) {
        String controllerType = TrafficSignalControllerType.fromCode(request.controllerType()).code();
        controllerRegistry.get(controllerType);
        destroyExistingSessionsBeforeCreate();
        var cityFlowResponse = cityFlowClient.createSimulation(
                new CityFlowCreateSimulationRequest(request.sceneId(), request.speed(), request.warmupSeconds())
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
        forwardLifecycleToCityFlow("start", sid);
        session.setState(SimulationSessionState.RUNNING);
    }

    public void pause(String sid) {
        SimulationRuntimeSession session = findSession(sid);
        forwardLifecycleToCityFlow("pause", sid);
        session.setState(SimulationSessionState.PAUSED);
    }

    public void stop(String sid) {
        SimulationRuntimeSession session = findSession(sid);
        forwardLifecycleToCityFlow("stop", sid);
        session.setState(SimulationSessionState.FINISHED);
    }

    public void publishNextFrame(SimulationRuntimeSession session) {
        if (session.getState() != SimulationSessionState.RUNNING) {
            return;
        }
        long totalStart = System.nanoTime();
        double previousSimTime = session.getSimTime();
        // Spring Boot owns WebSocket delivery; Python only advances CityFlow and returns frame data.
        long cityFlowStart = System.nanoTime();
        SimFrameData frameData = cityFlowClient.nextFrame(session.getSid());
        long cityFlowMs = elapsedMs(cityFlowStart);
        long strategyStart = System.nanoTime();
        var controlResult = strategyDispatchService.decideAndApply(session, frameData);
        long strategyMs = elapsedMs(strategyStart);
        var decisions = controlResult.decisions();
        SimFrameData publishFrame = controlResult.frameAfterApply() == null ? frameData : controlResult.frameAfterApply();
        long seq = session.nextSequence();
        session.setSimTime(publishFrame.simTime());
        long wsStart = System.nanoTime();
        if (!decisions.isEmpty()) {
            webSocketHandler.publish(session.getSid(), new WsMessage<>(
                    "1.0",
                    "control.decision",
                    session.getSid(),
                    seq,
                    session.getSimTime(),
                    TimeUtils.nowRfc3339(),
                    decisions
                ));
        }
        WsMessage<SimFrameData> message = new WsMessage<>(
                "1.0",
                "sim.frame",
                session.getSid(),
                seq,
                session.getSimTime(),
                TimeUtils.nowRfc3339(),
                publishFrame
        );
        webSocketHandler.publish(session.getSid(), message);
        long websocketMs = elapsedMs(wsStart);
        long totalMs = elapsedMs(totalStart);
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("sid", session.getSid());
        timing.put("sceneId", session.getSceneId());
        timing.put("controllerType", session.getControllerType());
        timing.put("seq", seq);
        timing.put("previousSimTime", previousSimTime);
        timing.put("simTime", publishFrame.simTime());
        timing.put("simDelta", Math.round((publishFrame.simTime() - previousSimTime) * 1000.0) / 1000.0);
        timing.put("cityFlowFrameMs", cityFlowMs);
        timing.put("strategyMs", strategyMs);
        timing.put("websocketMs", websocketMs);
        timing.put("totalMs", totalMs);
        timing.put("decisionCount", decisions.size());
        timing.put("vehicleCount", publishFrame.vehicles() == null ? 0 : publishFrame.vehicles().size());
        timing.put("signalCount", publishFrame.signals() == null ? 0 : publishFrame.signals().size());
        if (publishFrame.metrics() != null) {
            timing.put("activeVehicleCount", publishFrame.metrics().activeVehicleCount());
            timing.put("scheduledDepartureCount", publishFrame.metrics().scheduledDepartureCount());
            timing.put("queueCount", publishFrame.metrics().queueCount());
        }
        frameTimingLogger.append(timing);
        if (frameTimingLogger.shouldWarn(totalMs)) {
            log.warn(
                    "slow simulation frame. sid={}, controllerType={}, seq={}, simTime={}, simDelta={}, cityFlowFrameMs={}, strategyMs={}, websocketMs={}, totalMs={}, vehicles={}, decisions={}",
                    session.getSid(),
                    session.getControllerType(),
                    seq,
                    publishFrame.simTime(),
                    timing.get("simDelta"),
                    cityFlowMs,
                    strategyMs,
                    websocketMs,
                    totalMs,
                    timing.get("vehicleCount"),
                    decisions.size()
            );
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void forwardLifecycleToCityFlow(String action, String sid) {
        try {
            switch (action) {
                case "start" -> cityFlowClient.startSimulation(sid);
                case "pause" -> cityFlowClient.pauseSimulation(sid);
                case "stop" -> cityFlowClient.stopSimulation(sid);
                default -> throw new IllegalArgumentException("unsupported CityFlow lifecycle action: " + action);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "failed to forward simulation lifecycle to Python CityFlow; continue with backend session state. sid={}, action={}, error={}",
                    sid,
                    action,
                    ex.getMessage()
            );
        }
    }

    private void destroyExistingSessionsBeforeCreate() {
        var existingSessions = sessionRegistry.findAllSnapshot();
        if (existingSessions.isEmpty()) {
            return;
        }
        log.info("destroying existing simulation sessions before creating a new one. count={}", existingSessions.size());
        for (SimulationRuntimeSession existingSession : existingSessions) {
            try {
                existingSession.setState(SimulationSessionState.FINISHED);
                cityFlowClient.stopSimulation(existingSession.getSid());
            } catch (RuntimeException ex) {
                log.warn(
                        "failed to stop existing CityFlow session before creating a new one. sid={}, error={}",
                        existingSession.getSid(),
                        ex.getMessage()
                );
            }
        }
        sessionRegistry.clear();
    }

    private SimulationRuntimeSession findSession(String sid) {
        return sessionRegistry.find(sid)
                .orElseThrow(() -> new BusinessException("simulation session not found: " + sid));
    }
}
