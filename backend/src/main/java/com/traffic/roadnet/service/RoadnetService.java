package com.traffic.roadnet.service;

import com.traffic.cityflow.client.CityFlowClient;
import com.traffic.roadnet.dto.RoadnetResponse;
import org.springframework.stereotype.Service;

@Service
public class RoadnetService {

    private final CityFlowClient cityFlowClient;

    public RoadnetService(CityFlowClient cityFlowClient) {
        this.cityFlowClient = cityFlowClient;
    }

    public RoadnetResponse getRoadnet(String sceneId) {
        // Keep Python access behind CityFlowClient so roadnet callers stay independent of CityFlow transport details.
        return cityFlowClient.getRoadnet(sceneId);
    }
}
