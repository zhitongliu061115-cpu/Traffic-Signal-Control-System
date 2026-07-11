package com.traffic.simulation.session;

import java.util.UUID;

public class SimulationRuntimeSession {

    private final String sid;
    private final String sceneId;
    private final String controllerType;
    private final UUID telemetryRunId;
    private long sequence;
    private double simTime;
    private long lastTelemetrySampleAt;
    private SimulationSessionState state;

    public SimulationRuntimeSession(
            String sid,
            String sceneId,
            String controllerType,
            UUID telemetryRunId,
            SimulationSessionState state
    ) {
        this.sid = sid;
        this.sceneId = sceneId;
        this.controllerType = controllerType;
        this.telemetryRunId = telemetryRunId;
        this.state = state;
    }

    public String getSid() {
        return sid;
    }

    public String getSceneId() {
        return sceneId;
    }

    public String getControllerType() {
        return controllerType;
    }

    public UUID getTelemetryRunId() {
        return telemetryRunId;
    }

    public long nextSequence() {
        return ++sequence;
    }

    public double getSimTime() {
        return simTime;
    }

    public void setSimTime(double simTime) {
        this.simTime = simTime;
    }

    public SimulationSessionState getState() {
        return state;
    }

    public void setState(SimulationSessionState state) {
        this.state = state;
    }

    public synchronized boolean claimTelemetrySample(long now, long intervalMs, boolean force) {
        if (!force && now - lastTelemetrySampleAt < intervalMs) {
            return false;
        }
        lastTelemetrySampleAt = now;
        return true;
    }
}
