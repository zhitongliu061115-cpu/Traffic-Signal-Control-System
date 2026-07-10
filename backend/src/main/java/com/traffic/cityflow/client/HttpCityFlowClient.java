package com.traffic.cityflow.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.cityflow.dto.ApplyControlActionsRequest;
import com.traffic.cityflow.dto.ApplyControlActionsResponse;
import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.simulation.dto.CityFlowCreateSimulationRequest;
import com.traffic.simulation.dto.CityFlowCreateSimulationResponse;
import com.traffic.simulation.dto.SimFrameData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class HttpCityFlowClient implements CityFlowClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpCityFlowClient(
            @Value("${cityflow.base-url}") String baseUrl,
            @Value("${cityflow.api-token:}") String apiToken,
            @Value("${cityflow.client-id:local}") String clientId,
            ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> {
                    if (StringUtils.hasText(apiToken)) {
                        headers.set("X-CityFlow-Token", apiToken);
                    }
                    if (StringUtils.hasText(clientId)) {
                        headers.set("X-CityFlow-Client", clientId);
                    }
                })
                .build();
        this.objectMapper = objectMapper;
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
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(request))
                .retrieve()
                .body(CityFlowCreateSimulationResponse.class);
    }

    @Override
    public void startSimulation(String sid) {
        postLifecycle(sid, "start");
    }

    @Override
    public void pauseSimulation(String sid) {
        postLifecycle(sid, "pause");
    }

    @Override
    public void stopSimulation(String sid) {
        postLifecycle(sid, "stop");
    }

    @Override
    public ApplyControlActionsResponse applyControlActions(String sid, ApplyControlActionsRequest request) {
        return restClient.post()
                .uri("/cityflow/simulations/{sid}/actions", sid)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(request))
                .retrieve()
                .body(ApplyControlActionsResponse.class);
    }

    @Override
    public SimFrameData nextFrame(String sid) {
        return restClient.get()
                .uri("/cityflow/simulations/{sid}/frame", sid)
                .retrieve()
                .body(SimFrameData.class);
    }

    private void postLifecycle(String sid, String action) {
        restClient.post()
                .uri("/cityflow/simulations/{sid}/{action}", sid, action)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .toBodilessEntity();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize CityFlow request body", ex);
        }
    }
}
