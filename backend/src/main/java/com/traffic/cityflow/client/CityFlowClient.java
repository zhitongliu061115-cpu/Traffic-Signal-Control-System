package com.traffic.cityflow.client;

import com.traffic.cityflow.dto.ApplyControlActionsRequest;
import com.traffic.cityflow.dto.ApplyControlActionsResponse;
import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.simulation.dto.CityFlowCreateSimulationRequest;
import com.traffic.simulation.dto.CityFlowCreateSimulationResponse;
import com.traffic.simulation.dto.SimFrameData;

public interface CityFlowClient {

    /*
     * This is the only backend boundary that may call the Python CityFlow service.
     * Controllers and frontend code must never call Python directly.
     */
    RoadnetResponse getRoadnet(String sceneId);

    CityFlowCreateSimulationResponse createSimulation(CityFlowCreateSimulationRequest request);

    ApplyControlActionsResponse applyControlActions(String sid, ApplyControlActionsRequest request);

    SimFrameData nextFrame(String sid);
}
