package com.traffic.simulation.session;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SimulationSessionRegistry {

    private final Map<String, SimulationRuntimeSession> sessions = new ConcurrentHashMap<>();

    public SimulationRuntimeSession register(String sid, String sceneId, String controllerType) {
        SimulationRuntimeSession session = new SimulationRuntimeSession(sid, sceneId, controllerType, SimulationSessionState.CREATED);
        sessions.put(sid, session);
        return session;
    }

    public Optional<SimulationRuntimeSession> find(String sid) {
        return Optional.ofNullable(sessions.get(sid));
    }

    public Collection<SimulationRuntimeSession> findAll() {
        return sessions.values();
    }

    public List<SimulationRuntimeSession> findAllSnapshot() {
        return List.copyOf(sessions.values());
    }

    public void remove(String sid) {
        sessions.remove(sid);
    }

    public void clear() {
        sessions.clear();
    }
}
