package com.traffic.analysis.controller;

import com.traffic.analysis.dto.DataAnalysisBootstrapResponse;
import com.traffic.analysis.dto.DataAnalysisLiveUpdateResponse;
import com.traffic.analysis.service.DataAnalysisService;
import com.traffic.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data-analysis")
public class DataAnalysisController {

    private final DataAnalysisService dataAnalysisService;

    public DataAnalysisController(DataAnalysisService dataAnalysisService) {
        this.dataAnalysisService = dataAnalysisService;
    }

    @GetMapping("/bootstrap")
    public ApiResponse<DataAnalysisBootstrapResponse> bootstrap() {
        return ApiResponse.ok(dataAnalysisService.loadBootstrapData());
    }

    @GetMapping("/updates/next")
    public ApiResponse<DataAnalysisLiveUpdateResponse> nextUpdate(
            @RequestParam(defaultValue = "0") long cursor
    ) {
        return ApiResponse.ok(dataAnalysisService.loadNextUpdate(cursor).orElse(null));
    }
}
