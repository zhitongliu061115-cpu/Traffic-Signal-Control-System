package com.traffic.strategy.rl.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public CloudTrafficRClient(
            TrafficRProperties properties,
            TrafficRDecisionAuditLogger auditLogger,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
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
        String responseBody = restClient.post()
                .uri(normalizePath(properties.getPredictPath()))
                .body(request)
                .retrieve()
                .body(String.class);
        TrafficRPredictResponse response = parseJson(responseBody, TrafficRPredictResponse.class, "backend.predict");
        auditLogger.append("backend.predict.response", response);
        return response;
    }

    public TrafficRBatchPredictResponse predictBatch(TrafficRBatchPredictRequest request) {
        auditLogger.append("backend.predict-batch.request", request);
        String responseBody = restClient.post()
                .uri(normalizePath(properties.getBatchPredictPath()))
                .body(request)
                .retrieve()
                .body(String.class);
        TrafficRBatchPredictResponse response = parseJson(responseBody, TrafficRBatchPredictResponse.class, "backend.predict-batch");
        auditLogger.append("backend.predict-batch.response", response);
        return response;
    }

    private <T> T parseJson(String responseBody, Class<T> responseType, String eventPrefix) {
        if (responseBody == null || responseBody.isBlank()) {
            auditLogger.append(eventPrefix + ".parse-error", "empty response body");
            throw new IllegalStateException("Traffic-R returned empty response body");
        }
        try {
            return objectMapper.readValue(responseBody, responseType);
        } catch (JsonProcessingException ex) {
            auditLogger.append(eventPrefix + ".parse-error", new ParseErrorPayload(
                    ex.getClass().getSimpleName(),
                    ex.getOriginalMessage(),
                    preview(responseBody)
            ));
            throw new IllegalStateException("Traffic-R returned non-JSON or incompatible response: "
                    + ex.getOriginalMessage(), ex);
        }
    }

    private String preview(String responseBody) {
        int maxLength = 2_000;
        return responseBody.length() <= maxLength ? responseBody : responseBody.substring(0, maxLength);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/predict";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private record ParseErrorPayload(String errorType, String message, String responsePreview) {
    }
}
