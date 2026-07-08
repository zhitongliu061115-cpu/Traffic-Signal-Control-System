package com.traffic.simulation.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriTemplate;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SimulationWebSocketHandler extends TextWebSocketHandler {

    private static final UriTemplate SID_TEMPLATE = new UriTemplate("/ws/v1/simulations/{sid}");

    private final ObjectMapper objectMapper;
    private final Map<String, Set<WebSocketSession>> sessionsBySid = new ConcurrentHashMap<>();

    public SimulationWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sid = resolveSid(session);
        sessionsBySid.computeIfAbsent(sid, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionsBySid.values().forEach(sessions -> sessions.remove(session));
    }

    public void publish(String sid, Object message) {
        Set<WebSocketSession> sessions = sessionsBySid.getOrDefault(sid, Set.of());
        if (sessions.isEmpty()) {
            return;
        }
        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to publish simulation frame", ex);
        }
    }

    private String resolveSid(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        return SID_TEMPLATE.match(path).get("sid");
    }
}
