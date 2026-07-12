package com.traffic.emergency.service;

import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.common.exception.BusinessException;
import com.traffic.emergency.dto.CoordDTO;
import com.traffic.emergency.dto.EVDispatchRequest;
import com.traffic.emergency.dto.EVDispatchResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmergencyService {

    private final CityFlowClient cityFlowClient;

    public EmergencyService(CityFlowClient cityFlowClient) {
        this.cityFlowClient = cityFlowClient;
    }
    public EVDispatchResponse dispatch(String sid, EVDispatchRequest request) {
        Map<String, Object> pythonRequest = new HashMap<>();
        if (request.startCoord() != null && request.endCoord() != null) {
            pythonRequest.put("startCoord", coordToMap(request.startCoord()));
            pythonRequest.put("endCoord", coordToMap(request.endCoord()));
        }
        if (request.startIntersection() != null && !request.startIntersection().isBlank()) {
            pythonRequest.put("startIntersection", request.startIntersection());
            pythonRequest.put("endIntersection", request.endIntersection());
        }
        pythonRequest.put("evId", request.evId());
        pythonRequest.put("evType", request.evType());
        pythonRequest.put("priority", request.priority());
        pythonRequest.put("maxSpeed", request.maxSpeed());

        Map<String, Object> pythonResponse = cityFlowClient.dispatchEV(sid, pythonRequest);

        if (pythonResponse == null) {
            throw new BusinessException("Python dispatch returned null");
        }

        List<String> route = toStringList(pythonResponse.get("route"));
        List<String> routeRoads = toStringList(pythonResponse.get("routeRoads"));
        double travelTime = toDouble(pythonResponse.get("estimatedTravelTime"));

        String cfVehicleId = (String) pythonResponse.get("cfVehicleId");
        if (cfVehicleId == null || cfVehicleId.isBlank()) {
            cfVehicleId = "";
        }

        return new EVDispatchResponse(
                cfVehicleId,
                (String) pythonResponse.getOrDefault("sid", sid),
                (String) pythonResponse.getOrDefault("evId", request.evId()),
                (String) pythonResponse.getOrDefault("evType", request.evType()),
                ((Number) pythonResponse.getOrDefault("priority", request.priority())).intValue(),
                route,
                routeRoads,
                travelTime
        );
    }

    private Map<String, Object> coordToMap(CoordDTO coord) {
        Map<String, Object> map = new HashMap<>();
        map.put("x", coord.x());
        map.put("y", coord.y());
        return map;
    }
    private List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of();
    }

    private double toDouble(Object obj) {
        if (obj instanceof Number num) {
            return num.doubleValue();
        }
        return 0.0;
    }
}
