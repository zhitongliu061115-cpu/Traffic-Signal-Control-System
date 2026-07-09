package com.traffic.emergency.controller;

import com.traffic.common.response.ApiResponse;
import com.traffic.emergency.dto.EVDispatchRequest;
import com.traffic.emergency.dto.EVDispatchResponse;
import com.traffic.emergency.service.EmergencyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulations")
public class EmergencyController {

    private final EmergencyService emergencyService;

    public EmergencyController(EmergencyService emergencyService) {
        this.emergencyService = emergencyService;
    }

    @PostMapping("/{sid}/dispatch")
    public ApiResponse<EVDispatchResponse> dispatch(
            @PathVariable String sid,
            @Valid @RequestBody EVDispatchRequest request) {
        return ApiResponse.ok(emergencyService.dispatch(sid, request));
    }
}
