package com.traffic.simulation.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.simulation.dto.WsMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationWebSocketHandlerTest {

    @Test
    void publishReturnsBeforeSocketWriteAndKeepsOnlyLatestPendingFrame() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        SimulationWebSocketHandler handler = SimulationWebSocketHandler.createForTesting(
                new ObjectMapper(),
                executor
        );
        WebSocketSession session = session("session-1", "run-1");
        handler.afterConnectionEstablished(session);

        handler.publish("run-1", frame("run-1", 1));
        handler.publish("run-1", frame("run-1", 2));

        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any());
        assertEquals(1, executor.pendingTasks());

        executor.runAll();

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        assertEquals(2, new ObjectMapper().readTree(messageCaptor.getValue().getPayload()).get("seq").asLong());
    }

    @Test
    void failedConnectionIsRemovedWithoutPreventingOtherConnectionsFromSending() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        SimulationWebSocketHandler handler = SimulationWebSocketHandler.createForTesting(
                new ObjectMapper(),
                executor
        );
        WebSocketSession failed = session("failed", "run-1");
        WebSocketSession healthy = session("healthy", "run-1");
        doThrow(new IOException("write timeout")).when(failed).sendMessage(org.mockito.ArgumentMatchers.any());
        handler.afterConnectionEstablished(failed);
        handler.afterConnectionEstablished(healthy);

        handler.publish("run-1", frame("run-1", 1));
        executor.runAll();

        verify(healthy).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(failed).close(org.mockito.ArgumentMatchers.any());
        assertEquals(1, handler.snapshotStats().get("totalConnections"));
    }

    @Test
    void slowConnectionDoesNotDelayHealthyConnectionOnTheSameSid() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowFinished = new CountDownLatch(1);
        try {
            SimulationWebSocketHandler handler = SimulationWebSocketHandler.createForTesting(
                    new ObjectMapper(),
                    executor
            );
            WebSocketSession slow = session("slow", "run-1");
            WebSocketSession healthy = session("healthy", "run-1");
            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch healthySent = new CountDownLatch(1);
            doAnswer(ignored -> {
                slowStarted.countDown();
                try {
                    assertTrue(releaseSlow.await(2, TimeUnit.SECONDS));
                } finally {
                    slowFinished.countDown();
                }
                return null;
            }).when(slow).sendMessage(org.mockito.ArgumentMatchers.any());
            doAnswer(ignored -> {
                healthySent.countDown();
                return null;
            }).when(healthy).sendMessage(org.mockito.ArgumentMatchers.any());
            handler.afterConnectionEstablished(slow);
            handler.afterConnectionEstablished(healthy);

            handler.publish("run-1", frame("run-1", 1));

            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
            assertTrue(healthySent.await(1, TimeUnit.SECONDS));
            releaseSlow.countDown();
            assertTrue(slowFinished.await(1, TimeUnit.SECONDS));
        } finally {
            releaseSlow.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void fourBlockedConnectionsDoNotExhaustProductionSenders() throws Exception {
        SimulationWebSocketHandler handler = new SimulationWebSocketHandler(new ObjectMapper());
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowStarted = new CountDownLatch(4);
        CountDownLatch slowFinished = new CountDownLatch(4);
        try {
            for (int index = 0; index < 4; index++) {
                String sid = "slow-" + index;
                WebSocketSession slow = session("slow-session-" + index, sid);
                doAnswer(ignored -> {
                    slowStarted.countDown();
                    try {
                        assertTrue(releaseSlow.await(2, TimeUnit.SECONDS));
                    } finally {
                        slowFinished.countDown();
                    }
                    return null;
                }).when(slow).sendMessage(org.mockito.ArgumentMatchers.any());
                handler.afterConnectionEstablished(slow);
                handler.publish(sid, frame(sid, 1));
            }
            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));

            CountDownLatch healthySent = new CountDownLatch(1);
            WebSocketSession healthy = session("healthy", "healthy-sid");
            doAnswer(ignored -> {
                healthySent.countDown();
                return null;
            }).when(healthy).sendMessage(org.mockito.ArgumentMatchers.any());
            handler.afterConnectionEstablished(healthy);
            handler.publish("healthy-sid", frame("healthy-sid", 1));

            assertTrue(healthySent.await(1, TimeUnit.SECONDS));
        } finally {
            releaseSlow.countDown();
            slowFinished.await(1, TimeUnit.SECONDS);
            handler.shutdown();
        }
    }

    @Test
    void overflowingControlQueueClosesTheSlowConnection() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        SimulationWebSocketHandler handler = SimulationWebSocketHandler.createForTesting(
                new ObjectMapper(),
                executor
        );
        WebSocketSession session = session("session-1", "run-1");
        handler.afterConnectionEstablished(session);

        for (int index = 0; index < 33; index++) {
            handler.publish("run-1", Map.of("type", "control.decision", "seq", index));
        }

        verify(session).close(org.mockito.ArgumentMatchers.any());
        assertEquals(0, handler.snapshotStats().get("sidCount"));
        assertEquals(0, handler.snapshotStats().get("totalConnections"));
    }

    @Test
    void closingTheLastConnectionRemovesItsSidBucket() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        SimulationWebSocketHandler handler = SimulationWebSocketHandler.createForTesting(
                new ObjectMapper(),
                executor
        );
        WebSocketSession session = session("session-1", "run-1");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        assertEquals(0, handler.snapshotStats().get("sidCount"));
        assertEquals(0, handler.snapshotStats().get("totalConnections"));
    }

    @Test
    void connectionClosingDuringRegistrationDoesNotLeaveADeadSidBucket() throws Exception {
        ManualExecutor sendExecutor = new ManualExecutor();
        SimulationWebSocketHandler handler = SimulationWebSocketHandler.createForTesting(
                new ObjectMapper(),
                sendExecutor
        );
        WebSocketSession session = mock(WebSocketSession.class);
        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        AtomicBoolean firstIdLookup = new AtomicBoolean(true);
        when(session.getId()).thenAnswer(ignored -> {
            if (firstIdLookup.compareAndSet(true, false)) {
                registrationEntered.countDown();
                assertTrue(releaseRegistration.await(1, TimeUnit.SECONDS));
            }
            return "session-1";
        });
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/v1/simulations/run-1"));
        when(session.isOpen()).thenReturn(true);
        ExecutorService lifecycleExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<?> establish = lifecycleExecutor.submit(() -> handler.afterConnectionEstablished(session));
            assertTrue(registrationEntered.await(1, TimeUnit.SECONDS));
            Future<?> close = lifecycleExecutor.submit(() ->
                    handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL)
            );

            releaseRegistration.countDown();
            establish.get(1, TimeUnit.SECONDS);
            close.get(1, TimeUnit.SECONDS);

            assertEquals(0, handler.snapshotStats().get("sidCount"));
            assertEquals(0, handler.snapshotStats().get("totalConnections"));
        } finally {
            releaseRegistration.countDown();
            lifecycleExecutor.shutdownNow();
        }
    }

    private static WebSocketSession session(String id, String sid) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/v1/simulations/" + sid));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static WsMessage<Map<String, Object>> frame(String sid, long seq) {
        return new WsMessage<>(
                "1.0",
                "sim.frame",
                sid,
                seq,
                seq,
                "2026-07-13T00:00:00Z",
                Map.of("seq", seq)
        );
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int pendingTasks() {
            return tasks.size();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}
