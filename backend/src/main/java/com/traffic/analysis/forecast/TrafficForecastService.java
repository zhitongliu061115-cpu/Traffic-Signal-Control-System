package com.traffic.analysis.forecast;

import com.traffic.analysis.forecast.TrafficForecastDtos.ForecastResponse;
import com.traffic.analysis.forecast.TrafficForecastDtos.Observation;
import com.traffic.analysis.forecast.TrafficForecastDtos.PredictRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TrafficForecastService {

    private static final Logger log = LoggerFactory.getLogger(TrafficForecastService.class);

    private final TrafficForecastProperties properties;
    private final TrafficForecastRepository repository;
    private final TrafficForecastClient client;
    private volatile CachedForecast cache;

    public TrafficForecastService(
            TrafficForecastProperties properties,
            TrafficForecastRepository repository,
            TrafficForecastClient client
    ) {
        this.properties = properties;
        this.repository = repository;
        this.client = client;
    }

    public synchronized ForecastResponse loadForecast() {
        if (!properties.isEnabled()) {
            return ForecastResponse.unavailable("短时交通预测服务未启用");
        }
        CachedForecast current = cache;
        if (current != null && Duration.between(current.cachedAt(), Instant.now()).toSeconds()
                < properties.getCacheTtlSeconds()) {
            return current.response();
        }
        List<Observation> observations = repository.findPredictionObservations(
                properties.getHistoryDays(),
                properties.getRecentLookbackMinutes()
        );
        Map<String, Long> counts = observations.stream().collect(Collectors.groupingBy(
                Observation::intersectionId,
                Collectors.counting()
        ));
        long requiredObservations = properties.getHistoryDays() + properties.getRecentLookbackMinutes();
        long completeIntersections = counts.values().stream()
                .filter(count -> count >= requiredObservations)
                .count();
        if (completeIntersections < properties.getExpectedIntersections()) {
            return ForecastResponse.unavailable(
                    "预测历史数据不足：需要 " + properties.getExpectedIntersections()
                            + " 个路口各有最近 " + properties.getHistoryDays() + " 天同刻数据和 "
                            + properties.getRecentLookbackMinutes() + " 个连续分钟，当前满足 "
                            + completeIntersections + " 个路口"
            );
        }
        try {
            ForecastResponse response = client.predict(new PredictRequest(observations));
            if (response == null || !response.available()) {
                String message = response == null ? "预测服务未返回结果" : response.message();
                return ForecastResponse.unavailable(message);
            }
            cache = new CachedForecast(Instant.now(), response);
            return response;
        } catch (RuntimeException ex) {
            log.warn("traffic forecast request failed: {}", ex.getMessage());
            return ForecastResponse.unavailable("预测模型服务暂不可用");
        }
    }

    private record CachedForecast(Instant cachedAt, ForecastResponse response) {
    }
}
