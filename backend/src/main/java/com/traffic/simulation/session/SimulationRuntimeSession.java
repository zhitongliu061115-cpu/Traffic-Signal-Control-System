package com.traffic.simulation.session;

public class SimulationRuntimeSession {

    private final String sid;
    private final String sceneId;
    private final String controllerType;
    private long sequence;
    private double simTime;
    private SimulationSessionState state;

    public SimulationRuntimeSession(String sid, String sceneId, String controllerType, SimulationSessionState state) {
        this.sid = sid;
        this.sceneId = sceneId;
        this.controllerType = controllerType;
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
}
