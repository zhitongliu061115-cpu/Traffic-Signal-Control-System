package com.traffic.cityflow.client;

import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.simulation.dto.CityFlowCreateSimulationRequest;
import com.traffic.simulation.dto.CityFlowCreateSimulationResponse;
import com.traffic.simulation.dto.SimFrameData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpCityFlowClient implements CityFlowClient {

    private final RestClient restClient;

    public HttpCityFlowClient(@Value("${cityflow.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public RoadnetResponse getRoadnet(String sceneId) {
        return restClient.get()
                .uri("/cityflow/scenes/{sceneId}/roadnet", sceneId)
                .retrieve()
                .body(RoadnetResponse.class);
    }

    @Override
    public CityFlowCreateSimulationResponse createSimulation(CityFlowCreateSimulationRequest request) {
        return restClient.post()
                .uri("/cityflow/simulations")
                .body(request)
                .retrieve()
                .body(CityFlowCreateSimulationResponse.class);
    }

    @Override
    public SimFrameData nextFrame(String sid) {
        return restClient.get()
                .uri("/cityflow/simulations/{sid}/frame", sid)
                .retrieve()
                .body(SimFrameData.class);
    }
}
