package com.traffic.simulation.service;

import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.agent.service.AgentEmergencyDispatchMemory;
import com.traffic.common.exception.BusinessException;
import com.traffic.common.util.TimeUtils;
import com.traffic.runtime.persistence.RuntimePersistenceService;
import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.simulation.dto.CityFlowCreateSimulationRequest;
import com.traffic.simulation.dto.CreateSimulationRequest;
import com.traffic.simulation.dto.CreateSimulationResponse;
import com.traffic.simulation.dto.EvDispatchRequest;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.WsMessage;
import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.simulation.session.SimulationSessionRegistry;
import com.traffic.simulation.session.SimulationSessionState;
import com.traffic.simulation.state.LiveSimulationStateService;
import com.traffic.simulation.websocket.SimulationWebSocketHandler;
import com.traffic.strategy.TrafficSignalControllerType;
import com.traffic.strategy.dto.AppliedControlResult;
import com.traffic.strategy.service.TrafficSignalControllerRegistry;
import com.traffic.strategy.service.StrategyDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final RuntimePersistenceService runtimePersistenceService;
    private final LiveSimulationStateService liveSimulationStateService;
    private final AgentEmergencyDispatchMemory emergencyDispatchMemory;

    public SimulationService(
            CityFlowClient cityFlowClient,
            SimulationSessionRegistry sessionRegistry,
            SimulationWebSocketHandler webSocketHandler,
            TrafficSignalControllerRegistry controllerRegistry,
            StrategyDispatchService strategyDispatchService,
            SimulationFrameTimingLogger frameTimingLogger,
            RuntimePersistenceService runtimePersistenceService,
            LiveSimulationStateService liveSimulationStateService,
            AgentEmergencyDispatchMemory emergencyDispatchMemory
    ) {
        this.cityFlowClient = cityFlowClient;
        this.sessionRegistry = sessionRegistry;
        this.webSocketHandler = webSocketHandler;
        this.controllerRegistry = controllerRegistry;
        this.strategyDispatchService = strategyDispatchService;
        this.frameTimingLogger = frameTimingLogger;
        this.runtimePersistenceService = runtimePersistenceService;
        this.liveSimulationStateService = liveSimulationStateService;
        this.emergencyDispatchMemory = emergencyDispatchMemory;
    }

    public CreateSimulationResponse createSimulation(CreateSimulationRequest request) {
        String controllerType = TrafficSignalControllerType.fromCode(request.controllerType()).canonicalCode();
        controllerRegistry.get(controllerType);
        var cityFlowResponse = cityFlowClient.createSimulation(
                new CityFlowCreateSimulationRequest(request.sceneId(), request.speed(), request.warmupSeconds())
        );
        RoadnetResponse roadnet = null;
        try {
            roadnet = cityFlowClient.getRoadnet(cityFlowResponse.sceneId());
        } catch (RuntimeException ex) {
            log.warn("failed to load CityFlow roadnet for live cache. sceneId={}, error={}",
                    cityFlowResponse.sceneId(), ex.getMessage());
        }
        RoadnetResponse roadnetSnapshot = roadnet;
        runtimePersistenceService.ensureRoadnet(
                cityFlowResponse.sceneId(),
                () -> roadnetSnapshot
        );
        runtimePersistenceService.createSession(
                cityFlowResponse.sid(),
                cityFlowResponse.sceneId(),
                controllerType,
                request.speed(),
                request.warmupSeconds(),
                cityFlowResponse.status()
        );
        sessionRegistry.register(cityFlowResponse.sid(), cityFlowResponse.sceneId(), controllerType);
        liveSimulationStateService.registerSession(
                cityFlowResponse.sid(),
                cityFlowResponse.sceneId(),
                controllerType,
                cityFlowResponse.status(),
                roadnet
        );
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
        liveSimulationStateService.updateSessionStatus(sid, "running");
        runtimePersistenceService.updateSessionStatus(sid, "running");
    }

    public void pause(String sid) {
        SimulationRuntimeSession session = findSession(sid);
        forwardLifecycleToCityFlow("pause", sid);
        session.setState(SimulationSessionState.PAUSED);
        liveSimulationStateService.updateSessionStatus(sid, "paused");
        runtimePersistenceService.updateSessionStatus(sid, "paused");
    }

    public void stop(String sid) {
        SimulationRuntimeSession session = findSession(sid);
        forwardLifecycleToCityFlow("stop", sid);
        session.setState(SimulationSessionState.FINISHED);
        liveSimulationStateService.updateSessionStatus(sid, "finished");
        runtimePersistenceService.updateSessionStatus(sid, "finished");
        releaseSessionState(sid);
    }

    public Map<String, Object> dispatchEv(String sid, EvDispatchRequest request) {
        SimulationRuntimeSession session = findSession(sid);
        if (session.getState() != SimulationSessionState.RUNNING) {
            throw new BusinessException("simulation session is not running: " + sid);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (request.evId() != null && !request.evId().isBlank()) {
            params.put("evId", request.evId());
        }
        if (request.evType() != null && !request.evType().isBlank()) {
            params.put("evType", request.evType());
        }
        if (request.priority() != null) {
            params.put("priority", request.priority());
        }
        params.put("startIntersection", request.startIntersection());
        params.put("endIntersection", request.endIntersection());
        try {
            Map<String, Object> result = cityFlowClient.dispatchEV(sid, params);
            emergencyDispatchMemory.remember(
                    sid,
                    request.startIntersection(),
                    request.endIntersection(),
                    request.evId(),
                    request.evType(),
                    request.priority()
            );
            return result;
        } catch (RuntimeException ex) {
            log.warn("failed to dispatch EV to CityFlow. sid={}, error={}", sid, ex.getMessage());
            throw new BusinessException("EV dispatch failed: " + ex.getMessage());
        }
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
        boolean finished = "finished".equalsIgnoreCase(frameData.status());
        long strategyStart = System.nanoTime();
        var controlResult = finished
                ? new AppliedControlResult(List.of(), null)
                : strategyDispatchService.decideAndApply(session, frameData);
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
        liveSimulationStateService.recordFrame(session, seq, publishFrame, decisions);
        runtimePersistenceService.persistRuntimeEvents(session, publishFrame, decisions);
        if (finished) {
            session.setState(SimulationSessionState.FINISHED);
            liveSimulationStateService.updateSessionStatus(session.getSid(), "finished");
            runtimePersistenceService.updateSessionStatus(session.getSid(), "finished");
            releaseSessionState(session.getSid());
            log.info("simulation finished and released. sid={}, simTime={}", session.getSid(), publishFrame.simTime());
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

    private void releaseSessionState(String sid) {
        strategyDispatchService.releaseSession(sid);
        sessionRegistry.remove(sid);
    }

    private SimulationRuntimeSession findSession(String sid) {
        return sessionRegistry.find(sid)
                .orElseThrow(() -> new BusinessException("simulation session not found: " + sid));
    }
}
