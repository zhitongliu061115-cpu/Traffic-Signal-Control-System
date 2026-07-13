package com.traffic.analysis.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "traffic-forecast")
public class TrafficForecastProperties {

    private boolean enabled = true;
    private String baseUrl = "http://127.0.0.1:17008";
    private String predictPath = "/predict";
    private int timeoutSec = 10;
    private int recentLookbackMinutes = 30;
    private int historyDays = 14;
    private int expectedIntersections = 12;
    private int cacheTtlSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPredictPath() {
        return predictPath;
    }

    public void setPredictPath(String predictPath) {
        this.predictPath = predictPath;
    }

    public int getTimeoutSec() {
        return timeoutSec;
    }

    public void setTimeoutSec(int timeoutSec) {
        this.timeoutSec = timeoutSec;
    }

    public int getRecentLookbackMinutes() {
        return recentLookbackMinutes;
    }

    public void setRecentLookbackMinutes(int recentLookbackMinutes) {
        this.recentLookbackMinutes = recentLookbackMinutes;
    }

    public int getHistoryDays() {
        return historyDays;
    }

    public void setHistoryDays(int historyDays) {
        this.historyDays = historyDays;
    }

    public int getExpectedIntersections() {
        return expectedIntersections;
    }

    public void setExpectedIntersections(int expectedIntersections) {
        this.expectedIntersections = expectedIntersections;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }
}
