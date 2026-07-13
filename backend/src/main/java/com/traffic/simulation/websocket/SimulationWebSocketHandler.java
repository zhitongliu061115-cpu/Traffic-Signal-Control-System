package com.traffic.simulation.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.simulation.dto.WsMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriTemplate;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SimulationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulationWebSocketHandler.class);
    private static final UriTemplate SID_TEMPLATE = new UriTemplate("/ws/v1/simulations/{sid}");
    private static final AtomicInteger SEND_THREAD_SEQUENCE = new AtomicInteger();
    private static final int MAX_CONCURRENT_SENDERS = 64;
    private static final int MAX_PENDING_MESSAGES_PER_CONNECTION = 32;

    private final ObjectMapper objectMapper;
    private final Executor sendExecutor;
    private final ExecutorService ownedSendExecutor;
    private final Object connectionRegistryLock = new Object();
    private final Map<String, Set<OutboundConnection>> connectionsBySid = new ConcurrentHashMap<>();
    private final Map<String, OutboundConnection> connectionsBySessionId = new ConcurrentHashMap<>();

    @Autowired
    public SimulationWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.ownedSendExecutor = newSendExecutor();
        this.sendExecutor = ownedSendExecutor;
    }

    private SimulationWebSocketHandler(ObjectMapper objectMapper, Executor sendExecutor) {
        this.objectMapper = objectMapper;
        this.sendExecutor = sendExecutor;
        this.ownedSendExecutor = null;
    }

    static SimulationWebSocketHandler createForTesting(ObjectMapper objectMapper, Executor sendExecutor) {
        return new SimulationWebSocketHandler(objectMapper, sendExecutor);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sid = resolveSid(session);
        OutboundConnection connection = new OutboundConnection(sid, session);
        synchronized (connectionRegistryLock) {
            connectionsBySessionId.put(session.getId(), connection);
            connectionsBySid.compute(sid, (ignored, connections) -> {
                Set<OutboundConnection> target = connections == null
                        ? ConcurrentHashMap.newKeySet()
                        : connections;
                target.add(connection);
                return target;
            });
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        synchronized (connectionRegistryLock) {
            removeConnection(connectionsBySessionId.get(session.getId()));
        }
    }

    public void publish(String sid, Object message) {
        Set<OutboundConnection> connections = connectionsBySid.getOrDefault(sid, Set.of());
        if (connections.isEmpty()) {
            return;
        }

        final TextMessage textMessage;
        try {
            textMessage = new TextMessage(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize simulation WebSocket message", ex);
        }

        boolean replaceableFrame = message instanceof WsMessage<?> envelope
                && "sim.frame".equals(envelope.type());
        OutboundMessage outboundMessage = new OutboundMessage(textMessage, replaceableFrame);
        connections.forEach(connection -> connection.offer(outboundMessage));
    }

    public Map<String, Integer> snapshotStats() {
        int openConnections = 0;
        for (OutboundConnection connection : connectionsBySessionId.values()) {
            if (connection.session.isOpen()) {
                openConnections++;
            }
        }
        return Map.of(
                "sidCount", connectionsBySid.size(),
                "totalConnections", connectionsBySessionId.size(),
                "openConnections", openConnections
        );
    }

    @PreDestroy
    void shutdown() {
        if (ownedSendExecutor != null) {
            ownedSendExecutor.shutdownNow();
        }
    }

    private void removeConnection(OutboundConnection connection) {
        if (connection == null || !connection.close()) {
            return;
        }
        synchronized (connectionRegistryLock) {
            connectionsBySessionId.remove(connection.session.getId(), connection);
            connectionsBySid.computeIfPresent(connection.sid, (ignored, connections) -> {
                connections.remove(connection);
                return connections.isEmpty() ? null : connections;
            });
        }
    }

    private void failConnection(OutboundConnection connection, Exception error) {
        log.warn(
                "dropping slow or failed simulation WebSocket connection. sid={}, sessionId={}, error={}",
                connection.sid,
                connection.session.getId(),
                error.getMessage()
        );
        removeConnection(connection);
        try {
            if (connection.session.isOpen()) {
                connection.session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException closeError) {
            log.debug("failed to close simulation WebSocket session {}", connection.session.getId(), closeError);
        }
    }

    private String resolveSid(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        return SID_TEMPLATE.match(path).get("sid");
    }

    private static ExecutorService newSendExecutor() {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "simulation-ws-send-" + SEND_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                0,
                MAX_CONCURRENT_SENDERS,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private record OutboundMessage(TextMessage message, boolean replaceableFrame) {
    }

    private final class OutboundConnection {
        private final String sid;
        private final WebSocketSession session;
        private final Object queueLock = new Object();
        private final Deque<OutboundMessage> pending = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean draining;

        private OutboundConnection(String sid, WebSocketSession session) {
            this.sid = sid;
            this.session = session;
        }

        private void offer(OutboundMessage message) {
            boolean shouldSchedule = false;
            boolean queueOverflow = false;
            synchronized (queueLock) {
                if (closed.get()) {
                    return;
                }
                if (message.replaceableFrame()) {
                    pending.removeIf(OutboundMessage::replaceableFrame);
                }
                if (pending.size() >= MAX_PENDING_MESSAGES_PER_CONNECTION) {
                    queueOverflow = true;
                } else {
                    pending.addLast(message);
                    if (!draining) {
                        draining = true;
                        shouldSchedule = true;
                    }
                }
            }
            if (queueOverflow) {
                failConnection(this, new IllegalStateException("outbound message queue limit exceeded"));
                return;
            }
            if (shouldSchedule) {
                try {
                    sendExecutor.execute(this::drain);
                } catch (RejectedExecutionException ex) {
                    failConnection(this, ex);
                }
            }
        }

        private void drain() {
            while (true) {
                OutboundMessage next;
                synchronized (queueLock) {
                    if (closed.get()) {
                        pending.clear();
                        draining = false;
                        return;
                    }
                    next = pending.pollFirst();
                    if (next == null) {
                        draining = false;
                        return;
                    }
                }

                if (!session.isOpen()) {
                    removeConnection(this);
                    return;
                }
                try {
                    session.sendMessage(next.message());
                } catch (IOException | RuntimeException ex) {
                    failConnection(this, ex);
                    return;
                }
            }
        }

        private boolean close() {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            synchronized (queueLock) {
                pending.clear();
            }
            return true;
        }
    }
}
