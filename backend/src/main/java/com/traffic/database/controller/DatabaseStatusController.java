package com.traffic.database.controller;

import com.traffic.common.response.ApiResponse;
import com.traffic.database.dto.DatabaseStatusResponse;
import com.traffic.database.service.DatabaseStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/database")
public class DatabaseStatusController {

    private final DatabaseStatusService databaseStatusService;

    public DatabaseStatusController(DatabaseStatusService databaseStatusService) {
        this.databaseStatusService = databaseStatusService;
    }

    @GetMapping("/status")
    public ApiResponse<DatabaseStatusResponse> getStatus() {
        return ApiResponse.ok(databaseStatusService.getStatus());
    }
}
