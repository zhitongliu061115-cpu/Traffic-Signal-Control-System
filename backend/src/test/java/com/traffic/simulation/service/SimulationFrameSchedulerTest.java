package com.traffic.simulation.service;

import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.simulation.session.SimulationSessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationFrameSchedulerTest {

    @Test
    void oneFailedSessionDoesNotPreventOtherSessionsFromPublishing() {
        ManualExecutor executor = new ManualExecutor();
        SimulationSessionRegistry registry = mock(SimulationSessionRegistry.class);
        SimulationService simulationService = mock(SimulationService.class);
        SimulationRuntimeSession failed = session("failed");
        SimulationRuntimeSession healthy = session("healthy");
        when(registry.findAllSnapshot()).thenReturn(List.of(failed, healthy));
        doThrow(new IllegalStateException("send failed"))
                .when(simulationService)
                .publishNextFrame(failed);
        SimulationFrameScheduler scheduler = SimulationFrameScheduler.createForTesting(
                registry,
                simulationService,
                executor
        );

        scheduler.pollFrames();
        executor.runAll();

        verify(simulationService).publishNextFrame(failed);
        verify(simulationService).publishNextFrame(healthy);
    }

    @Test
    void slowSessionDoesNotDelayAnotherSession() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowFinished = new CountDownLatch(1);
        try {
            SimulationSessionRegistry registry = mock(SimulationSessionRegistry.class);
            SimulationService simulationService = mock(SimulationService.class);
            SimulationRuntimeSession slow = session("slow");
            SimulationRuntimeSession healthy = session("healthy");
            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch healthyFinished = new CountDownLatch(1);
            when(registry.findAllSnapshot()).thenReturn(List.of(slow, healthy));
            doAnswer(ignored -> {
                slowStarted.countDown();
                try {
                    assertTrue(releaseSlow.await(2, TimeUnit.SECONDS));
                } finally {
                    slowFinished.countDown();
                }
                return null;
            }).when(simulationService).publishNextFrame(slow);
            doAnswer(ignored -> {
                healthyFinished.countDown();
                return null;
            }).when(simulationService).publishNextFrame(healthy);
            SimulationFrameScheduler scheduler = SimulationFrameScheduler.createForTesting(
                    registry,
                    simulationService,
                    executor
            );

            scheduler.pollFrames();

            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
            assertTrue(healthyFinished.await(1, TimeUnit.SECONDS));
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
    void fourBlockedSessionsDoNotExhaustProductionFrameWorkers() throws Exception {
        SimulationSessionRegistry registry = mock(SimulationSessionRegistry.class);
        SimulationService simulationService = mock(SimulationService.class);
        List<SimulationRuntimeSession> sessions = new ArrayList<>();
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowStarted = new CountDownLatch(4);
        CountDownLatch slowFinished = new CountDownLatch(4);
        for (int index = 0; index < 4; index++) {
            SimulationRuntimeSession slow = session("slow-" + index);
            sessions.add(slow);
            doAnswer(ignored -> {
                slowStarted.countDown();
                try {
                    assertTrue(releaseSlow.await(2, TimeUnit.SECONDS));
                } finally {
                    slowFinished.countDown();
                }
                return null;
            }).when(simulationService).publishNextFrame(slow);
        }
        SimulationRuntimeSession healthy = session("healthy");
        sessions.add(healthy);
        CountDownLatch healthyFinished = new CountDownLatch(1);
        doAnswer(ignored -> {
            healthyFinished.countDown();
            return null;
        }).when(simulationService).publishNextFrame(healthy);
        when(registry.findAllSnapshot()).thenReturn(sessions);
        SimulationFrameScheduler scheduler = new SimulationFrameScheduler(registry, simulationService);
        try {
            scheduler.pollFrames();

            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
            assertTrue(healthyFinished.await(1, TimeUnit.SECONDS));
        } finally {
            releaseSlow.countDown();
            slowFinished.await(1, TimeUnit.SECONDS);
            scheduler.shutdown();
        }
    }

    @Test
    void sameSessionCannotAdvanceAgainWhileItsPreviousFrameIsInFlight() {
        ManualExecutor executor = new ManualExecutor();
        SimulationSessionRegistry registry = mock(SimulationSessionRegistry.class);
        SimulationService simulationService = mock(SimulationService.class);
        SimulationRuntimeSession session = session("run-1");
        when(registry.findAllSnapshot()).thenReturn(List.of(session));
        SimulationFrameScheduler scheduler = SimulationFrameScheduler.createForTesting(
                registry,
                simulationService,
                executor
        );

        scheduler.pollFrames();
        scheduler.pollFrames();

        assertEquals(1, executor.pendingTasks());
        executor.runAll();
        scheduler.pollFrames();
        executor.runAll();
        verify(simulationService, times(2)).publishNextFrame(session);
    }

    private static SimulationRuntimeSession session(String sid) {
        SimulationRuntimeSession session = mock(SimulationRuntimeSession.class);
        when(session.getSid()).thenReturn(sid);
        return session;
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
