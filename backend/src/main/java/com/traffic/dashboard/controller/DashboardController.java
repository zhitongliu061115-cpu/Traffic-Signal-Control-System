package com.traffic.dashboard.controller;

import com.traffic.common.response.ApiResponse;
import com.traffic.dashboard.dto.DashboardBootstrapResponse;
import com.traffic.dashboard.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/bootstrap")
    public ApiResponse<DashboardBootstrapResponse> bootstrap() {
        return ApiResponse.ok(dashboardService.loadBootstrapData());
    }
}
