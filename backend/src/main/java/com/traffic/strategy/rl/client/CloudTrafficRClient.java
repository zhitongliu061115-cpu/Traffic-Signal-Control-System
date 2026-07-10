package com.traffic.strategy.rl.client;

import com.traffic.strategy.rl.TrafficRProperties;
import com.traffic.strategy.rl.TrafficRDecisionAuditLogger;
import com.traffic.strategy.rl.dto.TrafficRBatchPredictRequest;
import com.traffic.strategy.rl.dto.TrafficRBatchPredictResponse;
import com.traffic.strategy.rl.dto.TrafficRPredictRequest;
import com.traffic.strategy.rl.dto.TrafficRPredictResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class CloudTrafficRClient {

    private final RestClient restClient;
    private final TrafficRProperties properties;
    private final TrafficRDecisionAuditLogger auditLogger;

    public CloudTrafficRClient(TrafficRProperties properties, TrafficRDecisionAuditLogger auditLogger) {
        this.properties = properties;
        this.auditLogger = auditLogger;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Duration.ofSeconds(properties.getTimeoutSec()).toMillis();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public TrafficRPredictResponse predict(TrafficRPredictRequest request) {
        auditLogger.append("backend.predict.request", request);
        TrafficRPredictResponse response = restClient.post()
                .uri(normalizePath(properties.getPredictPath()))
                .body(request)
                .retrieve()
                .body(TrafficRPredictResponse.class);
        auditLogger.append("backend.predict.response", response);
        return response;
    }

    public TrafficRBatchPredictResponse predictBatch(TrafficRBatchPredictRequest request) {
        auditLogger.append("backend.predict-batch.request", request);
        TrafficRBatchPredictResponse response = restClient.post()
                .uri(normalizePath(properties.getBatchPredictPath()))
                .body(request)
                .retrieve()
                .body(TrafficRBatchPredictResponse.class);
        auditLogger.append("backend.predict-batch.response", response);
        return response;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/predict";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
