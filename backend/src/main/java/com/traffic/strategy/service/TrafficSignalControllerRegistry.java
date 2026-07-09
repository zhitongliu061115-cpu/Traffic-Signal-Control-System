package com.traffic.strategy.service;

import com.traffic.common.exception.BusinessException;
import com.traffic.strategy.TrafficSignalController;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TrafficSignalControllerRegistry {

    private final Map<String, TrafficSignalController> controllers;

    public TrafficSignalControllerRegistry(List<TrafficSignalController> controllers) {
        this.controllers = controllers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        controller -> normalize(controller.controllerType()),
                        controller -> controller
                ));
    }

    public TrafficSignalController get(String controllerType) {
        TrafficSignalController controller = controllers.get(normalizeAlias(controllerType));
        if (controller == null) {
            throw new BusinessException("traffic signal controller not found: " + controllerType);
        }
        return controller;
    }

    private String normalizeAlias(String controllerType) {
        String normalized = normalize(controllerType);
        return "rl".equals(normalized) ? "traffic-r" : normalized;
    }

    private String normalize(String controllerType) {
        return controllerType.toLowerCase(Locale.ROOT).trim();
    }
}
