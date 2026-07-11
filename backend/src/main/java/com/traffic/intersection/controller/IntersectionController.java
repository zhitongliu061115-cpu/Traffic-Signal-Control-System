package com.traffic.intersection.controller;

import com.traffic.common.response.ApiResponse;
import com.traffic.intersection.dto.IntersectionResponse;
import com.traffic.intersection.dto.UpdateIntersectionStatusRequest;
import com.traffic.intersection.service.IntersectionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/intersections")
public class IntersectionController {

    private final IntersectionService intersectionService;

    public IntersectionController(IntersectionService intersectionService) {
        this.intersectionService = intersectionService;
    }

    @GetMapping
    public ApiResponse<List<IntersectionResponse>> findAll() {
        return ApiResponse.ok(intersectionService.findAll());
    }

    @GetMapping("/{code}")
    public ApiResponse<IntersectionResponse> findByCode(@PathVariable String code) {
        return ApiResponse.ok(intersectionService.findByCode(code));
    }

    @PatchMapping("/{code}/status")
    public ApiResponse<IntersectionResponse> updateStatus(
            @PathVariable String code,
            @Valid @RequestBody UpdateIntersectionStatusRequest request
    ) {
        return ApiResponse.ok(intersectionService.updateStatus(code, request.status()));
    }
}
