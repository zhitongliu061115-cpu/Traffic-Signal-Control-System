package com.traffic.scene.controller;

import com.traffic.common.response.ApiResponse;
import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.roadnet.service.RoadnetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scenes")
public class SceneController {

    private final RoadnetService roadnetService;

    public SceneController(RoadnetService roadnetService) {
        this.roadnetService = roadnetService;
    }

    @GetMapping("/{sceneId}/roadnet")
    public ApiResponse<RoadnetResponse> getRoadnet(@PathVariable String sceneId) {
        return ApiResponse.ok(roadnetService.getRoadnet(sceneId));
    }
}
