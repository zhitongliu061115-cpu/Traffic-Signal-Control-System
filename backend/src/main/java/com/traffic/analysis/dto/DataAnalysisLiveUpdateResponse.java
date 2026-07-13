package com.traffic.analysis.dto;

import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.CompositionItemDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.DashboardToastDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.HourlyPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MonitoringMetricDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MonitoringRecordDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.StatusBucketDto;
import java.util.List;

public record DataAnalysisLiveUpdateResponse(
        long cursor,
        int sampleCount,
        int healthScore,
        String sampledPointId,
        List<MonitoringMetricDto> metrics,
        List<StatusBucketDto> statusDistribution,
        HourlyPointDto hourlyPoint,
        List<CompositionItemDto> composition,
        MonitoringRecordDto record,
        DashboardToastDto toast
) {
}
