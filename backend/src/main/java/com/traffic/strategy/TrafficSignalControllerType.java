package com.traffic.strategy;

import com.traffic.common.exception.BusinessException;

import java.util.Arrays;

public enum TrafficSignalControllerType {
    FIXED_TIME("fixed-time"),
    MAX_PRESSURE("max-pressure"),
    RL("rl"),
    TRAFFIC_R("traffic-r");

    private final String code;

    TrafficSignalControllerType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static TrafficSignalControllerType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return FIXED_TIME;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("unsupported controllerType: " + code));
    }
}
