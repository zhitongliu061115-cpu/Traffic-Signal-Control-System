package com.traffic.analysis.dto;

import java.util.List;

public record DataAnalysisBootstrapResponse(
        int sampleCount,
        int sampleRate,
        int healthScore,
        String sampledPointId,
        List<MonitoringMetricDto> metrics,
        List<StatusBucketDto> statusDistribution,
        List<DailyPointDto> dailySeries,
        List<HourlyPointDto> hourlySeries,
        List<BuildingSummaryDto> buildingSummaries,
        List<HeatmapCellDto> heatmap,
        List<CompositionItemDto> composition,
        List<ScatterPointDto> scatterPoints,
        List<MonitoringRecordDto> records,
        List<DashboardToastDto> toasts
) {
    public record MonitoringMetricDto(String detail, String label, String tone, String value) {
    }

    public record StatusBucketDto(int count, String label, String tone) {
    }

    public record DailyPointDto(String date, double electricity, double hvac, double occupancy, double water) {
    }

    public record HourlyPointDto(String hour, double electricity, double hvac, double occupancy, double temperature) {
    }

    public record BuildingSummaryDto(
            double averageOccupancy,
            String buildingId,
            String buildingType,
            int efficiencyScore,
            double electricity,
            double hvac,
            String statusLabel,
            int warningCount,
            double water
    ) {
    }

    public record HeatmapCellDto(String date, double electricity, String hour, double intensity, double occupancy) {
    }

    public record CompositionItemDto(String color, String label, double value) {
    }

    public record ScatterPointDto(
            String buildingId,
            double electricity,
            String hour,
            String id,
            double occupancy,
            double temperature,
            String tone
    ) {
    }

    public record MonitoringRecordDto(
            long id,
            String building_id,
            String building_type,
            double chilled_water_return_temp,
            double chilled_water_supply_temp,
            String device_id,
            String device_status,
            String control_strategy,
            double electricity_kwh,
            double env_humidity,
            double env_temperature,
            double hvac_kwh,
            String monitor_time,
            double occupancy_density,
            double water_m3
    ) {
    }

    public record DashboardToastDto(long id, String body, String title, String tone) {
    }
}
