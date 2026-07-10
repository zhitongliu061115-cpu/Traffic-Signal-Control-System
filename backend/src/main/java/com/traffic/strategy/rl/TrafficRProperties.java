package com.traffic.strategy.rl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "traffic-r")
public class TrafficRProperties {

    private boolean enabled = true;
    private String baseUrl = "http://127.0.0.1:16008";
    private String healthPath = "/health";
    private String predictPath = "/predict";
    private String batchPredictPath = "/predict-batch";
    private int decisionIntervalSec = 10;
    private int timeoutSec = 30;
    private String fallbackController = "max-pressure";

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

    public String getHealthPath() {
        return healthPath;
    }

    public void setHealthPath(String healthPath) {
        this.healthPath = healthPath;
    }

    public String getPredictPath() {
        return predictPath;
    }

    public void setPredictPath(String predictPath) {
        this.predictPath = predictPath;
    }

    public String getBatchPredictPath() {
        return batchPredictPath;
    }

    public void setBatchPredictPath(String batchPredictPath) {
        this.batchPredictPath = batchPredictPath;
    }

    public int getDecisionIntervalSec() {
        return decisionIntervalSec;
    }

    public void setDecisionIntervalSec(int decisionIntervalSec) {
        this.decisionIntervalSec = decisionIntervalSec;
    }

    public int getTimeoutSec() {
        return timeoutSec;
    }

    public void setTimeoutSec(int timeoutSec) {
        this.timeoutSec = timeoutSec;
    }

    public String getFallbackController() {
        return fallbackController;
    }

    public void setFallbackController(String fallbackController) {
        this.fallbackController = fallbackController;
    }
}
