package com.traffic.dashboard.service;

import com.traffic.dashboard.dto.DashboardBootstrapResponse;
import com.traffic.dashboard.repository.DashboardRepository;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

    private final DashboardRepository dashboardRepository;

    public DashboardService(DashboardRepository dashboardRepository) {
        this.dashboardRepository = dashboardRepository;
    }

    public DashboardBootstrapResponse loadBootstrapData() {
        return dashboardRepository.loadBootstrapData();
    }
}
