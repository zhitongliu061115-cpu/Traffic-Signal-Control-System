package com.traffic.simulation.service;

import com.traffic.simulation.session.SimulationRuntimeSession;
import com.traffic.simulation.session.SimulationSessionRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SimulationFrameScheduler {

    private static final Logger log = LoggerFactory.getLogger(SimulationFrameScheduler.class);
    private static final AtomicInteger FRAME_THREAD_SEQUENCE = new AtomicInteger();
    private static final int MAX_CONCURRENT_FRAME_TASKS = 64;

    private final SimulationSessionRegistry sessionRegistry;
    private final SimulationService simulationService;
    private final Executor frameExecutor;
    private final ExecutorService ownedFrameExecutor;
    private final Set<String> inFlightSids = ConcurrentHashMap.newKeySet();

    @Autowired
    public SimulationFrameScheduler(SimulationSessionRegistry sessionRegistry, SimulationService simulationService) {
        this.sessionRegistry = sessionRegistry;
        this.simulationService = simulationService;
        this.ownedFrameExecutor = newFrameExecutor();
        this.frameExecutor = ownedFrameExecutor;
    }

    private SimulationFrameScheduler(
            SimulationSessionRegistry sessionRegistry,
            SimulationService simulationService,
            Executor frameExecutor
    ) {
        this.sessionRegistry = sessionRegistry;
        this.simulationService = simulationService;
        this.frameExecutor = frameExecutor;
        this.ownedFrameExecutor = null;
    }

    static SimulationFrameScheduler createForTesting(
            SimulationSessionRegistry sessionRegistry,
            SimulationService simulationService,
            Executor frameExecutor
    ) {
        return new SimulationFrameScheduler(sessionRegistry, simulationService, frameExecutor);
    }

    @Scheduled(fixedDelayString = "${cityflow.frame-poll-interval-ms:1000}")
    public void pollFrames() {
        for (SimulationRuntimeSession session : sessionRegistry.findAllSnapshot()) {
            String sid = session.getSid();
            if (!inFlightSids.add(sid)) {
                continue;
            }
            try {
                frameExecutor.execute(() -> publishFrame(session, sid));
            } catch (RejectedExecutionException ex) {
                inFlightSids.remove(sid);
                log.warn("simulation frame executor is full. sid={}", sid);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedFrameExecutor != null) {
            ownedFrameExecutor.shutdownNow();
        }
    }

    private void publishFrame(SimulationRuntimeSession session, String sid) {
        try {
            simulationService.publishNextFrame(session);
        } catch (RuntimeException ex) {
            log.error("failed to publish simulation frame. sid={}", sid, ex);
        } finally {
            inFlightSids.remove(sid);
        }
    }

    private static ExecutorService newFrameExecutor() {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "simulation-frame-" + FRAME_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                0,
                MAX_CONCURRENT_FRAME_TASKS,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
