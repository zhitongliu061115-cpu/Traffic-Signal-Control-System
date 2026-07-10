package com.traffic.analysis.repository;

import com.traffic.analysis.dto.DataAnalysisBootstrapResponse;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.BuildingSummaryDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.CompositionItemDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.DailyPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.DashboardToastDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.HeatmapCellDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.HourlyPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MonitoringMetricDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MonitoringRecordDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.ScatterPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.StatusBucketDto;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataAnalysisRepository {

    private final JdbcTemplate jdbcTemplate;

    public DataAnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DataAnalysisBootstrapResponse loadBootstrapData() {
        DataAnalysisOverview overview = findOverview();
        return new DataAnalysisBootstrapResponse(
                overview.sampleCount(),
                overview.sampleRate(),
                overview.healthScore(),
                overview.sampledPointId(),
                findMetrics(),
                findStatusDistribution(),
                findDailySeries(),
                findHourlySeries(),
                findBuildingSummaries(),
                findHeatmap(),
                findComposition(),
                findScatterPoints(),
                findRecords(),
                findToasts()
        );
    }

    private DataAnalysisOverview findOverview() {
        return jdbcTemplate.queryForObject("""
                select sample_count, sample_rate, health_score, sampled_point_id
                from analytics_overview
                where id = 1
                """, (rs, rowNum) -> new DataAnalysisOverview(
                rs.getInt("sample_count"),
                rs.getInt("sample_rate"),
                rs.getInt("health_score"),
                rs.getString("sampled_point_id")
        ));
    }

    private List<MonitoringMetricDto> findMetrics() {
        return jdbcTemplate.query("""
                select detail, label, tone, metric_value
                from analytics_metric
                order by sequence_no
                """, (rs, rowNum) -> new MonitoringMetricDto(
                rs.getString("detail"),
                rs.getString("label"),
                rs.getString("tone"),
                rs.getString("metric_value")
        ));
    }

    private List<StatusBucketDto> findStatusDistribution() {
        return jdbcTemplate.query("""
                select bucket_count, label, tone
                from analytics_status_bucket
                order by sequence_no
                """, (rs, rowNum) -> new StatusBucketDto(
                rs.getInt("bucket_count"),
                rs.getString("label"),
                rs.getString("tone")
        ));
    }

    private List<DailyPointDto> findDailySeries() {
        return jdbcTemplate.query("""
                select date_label, electricity, hvac, occupancy, water
                from analytics_daily_point
                order by sequence_no
                """, (rs, rowNum) -> new DailyPointDto(
                rs.getString("date_label"),
                rs.getDouble("electricity"),
                rs.getDouble("hvac"),
                rs.getDouble("occupancy"),
                rs.getDouble("water")
        ));
    }

    private List<HourlyPointDto> findHourlySeries() {
        return jdbcTemplate.query("""
                select hour_label, electricity, hvac, occupancy, temperature
                from analytics_hourly_point
                order by sequence_no
                """, (rs, rowNum) -> new HourlyPointDto(
                rs.getString("hour_label"),
                rs.getDouble("electricity"),
                rs.getDouble("hvac"),
                rs.getDouble("occupancy"),
                rs.getDouble("temperature")
        ));
    }

    private List<BuildingSummaryDto> findBuildingSummaries() {
        return jdbcTemplate.query("""
                select average_occupancy, building_id, building_type, efficiency_score,
                       electricity, hvac, status_label, warning_count, water
                from analytics_building_summary
                order by sequence_no
                """, (rs, rowNum) -> new BuildingSummaryDto(
                rs.getDouble("average_occupancy"),
                rs.getString("building_id"),
                rs.getString("building_type"),
                rs.getInt("efficiency_score"),
                rs.getDouble("electricity"),
                rs.getDouble("hvac"),
                rs.getString("status_label"),
                rs.getInt("warning_count"),
                rs.getDouble("water")
        ));
    }

    private List<HeatmapCellDto> findHeatmap() {
        return jdbcTemplate.query("""
                select date_label, electricity, hour_label, intensity, occupancy
                from analytics_heatmap_cell
                order by sequence_no
                """, (rs, rowNum) -> new HeatmapCellDto(
                rs.getString("date_label"),
                rs.getDouble("electricity"),
                rs.getString("hour_label"),
                rs.getDouble("intensity"),
                rs.getDouble("occupancy")
        ));
    }

    private List<CompositionItemDto> findComposition() {
        return jdbcTemplate.query("""
                select color, label, item_value
                from analytics_composition_item
                order by sequence_no
                """, (rs, rowNum) -> new CompositionItemDto(
                rs.getString("color"),
                rs.getString("label"),
                rs.getDouble("item_value")
        ));
    }

    private List<ScatterPointDto> findScatterPoints() {
        return jdbcTemplate.query("""
                select building_id, electricity, hour_label, point_id, occupancy, temperature, tone
                from analytics_scatter_point
                order by sequence_no
                """, (rs, rowNum) -> new ScatterPointDto(
                rs.getString("building_id"),
                rs.getDouble("electricity"),
                rs.getString("hour_label"),
                rs.getString("point_id"),
                rs.getDouble("occupancy"),
                rs.getDouble("temperature"),
                rs.getString("tone")
        ));
    }

    private List<MonitoringRecordDto> findRecords() {
        return jdbcTemplate.query("""
                select record_id, building_id, building_type, chilled_water_return_temp,
                       chilled_water_supply_temp, device_id, device_status, control_strategy, electricity_kwh,
                       env_humidity, env_temperature, hvac_kwh, monitor_time,
                       occupancy_density, water_m3
                from analytics_monitoring_record
                order by sequence_no
                """, (rs, rowNum) -> new MonitoringRecordDto(
                rs.getLong("record_id"),
                rs.getString("building_id"),
                rs.getString("building_type"),
                rs.getDouble("chilled_water_return_temp"),
                rs.getDouble("chilled_water_supply_temp"),
                rs.getString("device_id"),
                rs.getString("device_status"),
                rs.getString("control_strategy"),
                rs.getDouble("electricity_kwh"),
                rs.getDouble("env_humidity"),
                rs.getDouble("env_temperature"),
                rs.getDouble("hvac_kwh"),
                rs.getString("monitor_time"),
                rs.getDouble("occupancy_density"),
                rs.getDouble("water_m3")
        ));
    }

    private List<DashboardToastDto> findToasts() {
        return jdbcTemplate.query("""
                select toast_id, body, title, tone
                from analytics_toast
                order by sequence_no
                """, (rs, rowNum) -> new DashboardToastDto(
                rs.getLong("toast_id"),
                rs.getString("body"),
                rs.getString("title"),
                rs.getString("tone")
        ));
    }

    private record DataAnalysisOverview(int sampleCount, int sampleRate, int healthScore, String sampledPointId) {
    }
}
