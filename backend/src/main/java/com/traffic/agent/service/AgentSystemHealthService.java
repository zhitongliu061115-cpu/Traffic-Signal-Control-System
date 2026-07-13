package com.traffic.agent.service;

import com.traffic.runtime.query.RuntimeQueryDtos.SystemHealthResponse;
import com.traffic.runtime.query.RuntimeQueryService;
import com.traffic.simulation.state.LiveSimulationStateService;
import com.traffic.simulation.websocket.SimulationWebSocketHandler;
import com.traffic.strategy.rl.TrafficRProperties;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class AgentSystemHealthService {

    private final DataSource dataSource;
    private final RuntimeQueryService runtimeQueryService;
    private final LiveSimulationStateService liveSimulationStateService;
    private final SimulationWebSocketHandler webSocketHandler;
    private final TrafficRProperties trafficRProperties;
    private final RestClient cityFlowRestClient;
    private final RestClient trafficRRestClient;
    private final String cityFlowBaseUrl;
    private final String cityFlowHealthPath;

    public AgentSystemHealthService(
            DataSource dataSource,
            RuntimeQueryService runtimeQueryService,
            LiveSimulationStateService liveSimulationStateService,
            SimulationWebSocketHandler webSocketHandler,
            TrafficRProperties trafficRProperties,
            @Value("${cityflow.base-url}") String cityFlowBaseUrl,
            @Value("${cityflow.health-path:/health}") String cityFlowHealthPath
    ) {
        this.dataSource = dataSource;
        this.runtimeQueryService = runtimeQueryService;
        this.liveSimulationStateService = liveSimulationStateService;
        this.webSocketHandler = webSocketHandler;
        this.trafficRProperties = trafficRProperties;
        this.cityFlowBaseUrl = trimTrailingSlash(cityFlowBaseUrl);
        this.cityFlowHealthPath = normalizePath(cityFlowHealthPath);
        this.cityFlowRestClient = RestClient.builder()
                .baseUrl(this.cityFlowBaseUrl)
                .requestFactory(requestFactory())
                .build();
        this.trafficRRestClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(trafficRProperties.getBaseUrl()))
                .requestFactory(requestFactory())
                .build();
    }

    public EnhancedSystemHealth getSystemHealth(int limit) {
        SystemHealthResponse databasePerspective = runtimeQueryService.getSystemHealth(limit);
        Map<String, ComponentHealth> components = new LinkedHashMap<>();
        components.put("spring-boot", up("spring-boot", Map.of("status", "application-context-running")));
        components.put("database", probeDatabase());
        components.put("cityflow", probeHttp("cityflow", cityFlowRestClient, cityFlowHealthPath,
                Map.of("baseUrl", cityFlowBaseUrl, "healthPath", cityFlowHealthPath)));
        components.put("traffic-r", probeTrafficR());
        components.put("traffic-r-tunnel", deriveTunnelHealth(components.get("traffic-r")));
        components.put("websocket", up("websocket", webSocketHandler.snapshotStats()));
        components.put("live-simulation-cache", up("live-simulation-cache", Map.of(
                "sessions", liveSimulationStateService.listLiveSessions()
        )));

        boolean healthy = components.values().stream()
                .filter(item -> !"UNKNOWN".equals(item.status()))
                .allMatch(item -> "UP".equals(item.status()) || "DISABLED".equals(item.status()));
        List<String> warnings = components.values().stream()
                .filter(item -> !"UP".equals(item.status()) && !"DISABLED".equals(item.status()))
                .map(item -> item.name() + "=" + item.status() + ": " + item.message())
                .toList();
        return new EnhancedSystemHealth(
                healthy ? "UP" : "DEGRADED",
                components,
                databasePerspective,
                warnings,
                Instant.now()
        );
    }

    private ComponentHealth probeDatabase() {
        long start = System.nanoTime();
        try (var connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return new ComponentHealth(
                    "database",
                    valid ? "UP" : "DOWN",
                    elapsedMs(start),
                    valid ? "database connection is valid" : "database connection is invalid",
                    Map.of(
                            "url", safe(connection.getMetaData().getURL()),
                            "product", safe(connection.getMetaData().getDatabaseProductName())
                    ),
                    Instant.now()
            );
        } catch (Exception ex) {
            return down("database", start, ex, Map.of());
        }
    }

    private ComponentHealth probeTrafficR() {
        if (!trafficRProperties.isEnabled()) {
            return new ComponentHealth(
                    "traffic-r",
                    "DISABLED",
                    0,
                    "Traffic-R is disabled by configuration",
                    Map.of("baseUrl", trafficRProperties.getBaseUrl()),
                    Instant.now()
            );
        }
        return probeHttp("traffic-r", trafficRRestClient, normalizePath(trafficRProperties.getHealthPath()), Map.of(
                "baseUrl", trafficRProperties.getBaseUrl(),
                "healthPath", normalizePath(trafficRProperties.getHealthPath()),
                "predictPath", trafficRProperties.getPredictPath(),
                "batchPredictPath", trafficRProperties.getBatchPredictPath(),
                "timeoutSec", trafficRProperties.getTimeoutSec()
        ));
    }

    private ComponentHealth deriveTunnelHealth(ComponentHealth trafficR) {
        boolean localEndpoint = isLocalhost(trafficRProperties.getBaseUrl());
        if (!localEndpoint) {
            return new ComponentHealth(
                    "traffic-r-tunnel",
                    "UNKNOWN",
                    0,
                    "Traffic-R baseUrl is not a localhost tunnel endpoint; tunnel status cannot be inferred",
                    Map.of("baseUrl", trafficRProperties.getBaseUrl()),
                    Instant.now()
            );
        }
        String status = "UP".equals(trafficR.status()) ? "UP" : "DOWN";
        return new ComponentHealth(
                "traffic-r-tunnel",
                status,
                trafficR.latencyMs(),
                "UP".equals(status)
                        ? "localhost tunnel endpoint is reachable through Traffic-R health check"
                        : "localhost tunnel endpoint is not reachable through Traffic-R health check",
                Map.of("baseUrl", trafficRProperties.getBaseUrl()),
                Instant.now()
        );
    }

    private ComponentHealth probeHttp(
            String name,
            RestClient restClient,
            String path,
            Map<String, Object> details
    ) {
        long start = System.nanoTime();
        try {
            Object body = restClient.get()
                    .uri(path)
                    .retrieve()
                    .body(Object.class);
            Map<String, Object> merged = new LinkedHashMap<>(details);
            merged.put("responsePreview", body == null ? "" : String.valueOf(body));
            return new ComponentHealth(name, "UP", elapsedMs(start), "health endpoint reachable", merged, Instant.now());
        } catch (RuntimeException ex) {
            return down(name, start, ex, details);
        }
    }

    private ComponentHealth up(String name, Map<String, ?> details) {
        return new ComponentHealth(name, "UP", 0, "component is available", new LinkedHashMap<>(details), Instant.now());
    }

    private ComponentHealth down(String name, long start, Exception ex, Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put("errorType", ex.getClass().getSimpleName());
        return new ComponentHealth(name, "DOWN", elapsedMs(start), safe(ex.getMessage()), merged, Instant.now());
    }

    private int elapsedMs(long start) {
        return (int) Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        return factory;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/health";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isLocalhost(String value) {
        try {
            String host = URI.create(value).getHost();
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return "127.0.0.1".equals(lower) || "localhost".equals(lower) || "::1".equals(lower);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record EnhancedSystemHealth(
            String overallStatus,
            Map<String, ComponentHealth> components,
            SystemHealthResponse databasePerspective,
            List<String> warnings,
            Instant checkedAt
    ) {
    }

    public record ComponentHealth(
            String name,
            String status,
            int latencyMs,
            String message,
            Map<String, Object> details,
            Instant checkedAt
    ) {
    }
}
