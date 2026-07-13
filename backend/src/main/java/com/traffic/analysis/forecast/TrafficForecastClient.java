package com.traffic.analysis.forecast;

import com.traffic.analysis.forecast.TrafficForecastDtos.ForecastResponse;
import com.traffic.analysis.forecast.TrafficForecastDtos.PredictRequest;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TrafficForecastClient {

    private final RestClient restClient;
    private final TrafficForecastProperties properties;

    public TrafficForecastClient(TrafficForecastProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Duration.ofSeconds(properties.getTimeoutSec()).toMillis();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public ForecastResponse predict(PredictRequest request) {
        return restClient.post()
                .uri(normalizePath(properties.getPredictPath()))
                .body(request)
                .retrieve()
                .body(ForecastResponse.class);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/predict";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
